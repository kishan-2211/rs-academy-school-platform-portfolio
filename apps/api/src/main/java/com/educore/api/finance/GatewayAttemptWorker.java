package com.educore.api.finance;

import com.educore.api.tenancy.TenantRequestContext;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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

/**
 * One bounded internal attempt per invocation. CREATE and GET are separate
 * leases: commit one-use admission before POST, then recover only the same ID.
 * No network runs under tenant context or a database transaction. URL storage
 * cannot veto genuine money evidence; only the existing finalizer posts fees.
 */
@Service
public final class GatewayAttemptWorker {
    private static final Duration GET_RESERVE = Duration.ofSeconds(45);
    private static final Duration CREATE_RESERVE = Duration.ofSeconds(30);
    private static final Duration MAX_GET = Duration.ofSeconds(40);
    private static final Duration MAX_CREATE = Duration.ofSeconds(20);
    private final GatewayProviderRegistry registry;
    private final NamedParameterJdbcTemplate jdbc;
    private final GatewayWorkQueue queue;
    private final GatewayAttemptDispatch dispatch;
    private final GatewayCreateCommandAdmission createAdmission;
    private final GatewayProviderDiscovery discovery;
    private final GatewayHostedCheckoutStore hosted;
    private final GatewayObservationFinalizer finalizer;
    private final Clock clock;
    private final TransactionTemplate transactions;
    // One bounded HTTP client/credential instance per immutable configured route,
    // not one new selector thread per poll. Environment-key rotation requires an
    // operator restart; database binding/status is still revalidated each time.
    private final ConcurrentHashMap<GatewayProviderRegistry.Route, PaymentProviderAdapter> providers = new ConcurrentHashMap<>();

    public GatewayAttemptWorker(GatewayProviderRegistry registry, NamedParameterJdbcTemplate jdbc,
            GatewayWorkQueue queue, GatewayAttemptDispatch dispatch, GatewayCreateCommandAdmission createAdmission,
            GatewayProviderDiscovery discovery, GatewayHostedCheckoutStore hosted, GatewayObservationFinalizer finalizer,
            PlatformTransactionManager manager, Clock clock) {
        this.registry = Objects.requireNonNull(registry); this.jdbc = Objects.requireNonNull(jdbc);
        this.queue = Objects.requireNonNull(queue); this.dispatch = Objects.requireNonNull(dispatch);
        this.createAdmission = Objects.requireNonNull(createAdmission); this.discovery = Objects.requireNonNull(discovery);
        this.hosted = Objects.requireNonNull(hosted); this.finalizer = Objects.requireNonNull(finalizer);
        this.clock = Objects.requireNonNull(clock);
        transactions = new TransactionTemplate(Objects.requireNonNull(manager));
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transactions.setTimeout(15); transactions.setReadOnly(false);
    }

    public enum Step { DISABLED, IDLE, RECOVERED, RETRY, COMPLETE, REVIEW_REQUIRED, UNAVAILABLE, FENCED_OUT }

    public Step runNext(String routeKey) {
        requireOutsidePersistence();
        if (!registry.workerEnabled()) return Step.DISABLED;
        if (Thread.currentThread().isInterrupted()) return Step.FENCED_OUT;
        var route = registry.findRoute(routeKey).orElse(null);
        if (route == null) return Step.UNAVAILABLE;
        if (route.environment() == CashfreePaymentProviderAdapter.Environment.LIVE && !registry.liveEnabled()) return Step.DISABLED;
        var binding = binding(route);
        TimedClaim admitted;
        try { admitted = claim(routeKey, binding); }
        catch (RuntimeException unavailable) { return Step.FENCED_OUT; }
        if (admitted.claim() == null) return admitted.recovered() ? Step.RECOVERED : Step.IDLE;
        var claim = admitted.claim();
        PaymentProviderAdapter provider;
        try {
            // No credential lookup or provider construction inside persistence.
            // Do this BEFORE consuming a never-used CREATE marker.
            provider = providers.computeIfAbsent(route, registry::provider);
        } catch (RuntimeException unavailable) {
            return retry(routeKey, claim, "GATEWAY_PROVIDER_UNAVAILABLE", normalRetry(claim));
        }
        if (Thread.currentThread().isInterrupted()) return Step.FENCED_OUT;
        if (!admitted.snapshot().createPreviouslyDispatched()) {
            if (!registry.creationEnabled()) return retry(routeKey, claim, "GATEWAY_NEW_CREATE_DISABLED", normalRetry(claim));
            GatewayCreateCommandAdmission.Command command;
            long beforeCreateAdmission = System.nanoTime();
            try { command = createAdmission.admit(routeKey, claim); }
            catch (RuntimeException unavailable) {
                return retry(routeKey, claim, "GATEWAY_CREATE_ADMISSION_UNAVAILABLE", normalRetry(claim));
            }
            // This DB instant is sampled during admission; measuring from before
            // admission conservatively includes its entire query/commit latency.
            Duration budget = available(claim.leaseUntil(), command.admission().dispatchedAt(), beforeCreateAdmission,
                    CREATE_RESERVE, MAX_CREATE);
            var checkout = budget.isPositive() ? create(provider, command.request(), budget) : null;
            String code = "GATEWAY_CREATE_OUTCOME_UNKNOWN";
            Duration retryAfter = normalRetry(claim);
            if (checkout != null) {
                try {
                    var retained = hosted.retainCreate(routeKey, claim, checkout, clock.instant());
                    code = retained.conflicting() ? "GATEWAY_CREATE_LINK_REVIEW_REQUIRED" : "GATEWAY_CREATE_REQUIRES_GET_CONFIRMATION";
                } catch (RuntimeException unavailable) {
                    // The marker remains consumed. A later same-ID GET can
                    // recover a URL even after crash/encryption failure.
                    code = "GATEWAY_CREATE_LINK_OUTCOME_UNKNOWN";
                }
                retryAfter = Duration.ofSeconds(1);
            }
            return retry(routeKey, claim, code, retryAfter);
        }

        Duration budget = available(claim.leaseUntil(), admitted.checkedAt(), admitted.nanoBeforeClockQuery(), GET_RESERVE, MAX_GET);
        if (!budget.isPositive()) return retry(routeKey, claim, "GATEWAY_READ_BUDGET_UNAVAILABLE", normalRetry(claim));
        GatewayProviderDiscovery.ReadResult read;
        try { read = discovery.read(provider, admitted.snapshot(), budget); }
        catch (RuntimeException unavailable) {
            return retry(routeKey, claim, "GATEWAY_READ_OUTCOME_UNKNOWN", normalRetry(claim));
        }
        // Retention is deliberately independent of finance. A conflicting URL,
        // bad key or lost lease must not discard authenticated successful money.
        try { hosted.retainGet(routeKey, claim, read); }
        catch (RuntimeException unavailable) { /* Finalizer must still receive these actual GET facts. */ }
        try {
            return switch (finalizer.finalizeObservation(routeKey, claim, read.evidence())) {
                case RETRY -> Step.RETRY;
                case COMPLETE -> Step.COMPLETE;
                case REVIEW_REQUIRED -> Step.REVIEW_REQUIRED;
            };
        } catch (RuntimeException unknown) {
            // Its first transaction may already have committed facts. Do not
            // overwrite processing with guessed completion; recover real expiry.
            return Step.FENCED_OUT;
        }
    }

    private TimedClaim claim(String routeKey, GatewayWorkQueue.ProviderBinding binding) {
        try (var ignored = TenantRequestContext.open(null, binding.organisationId())) {
            return Objects.requireNonNull(transactions.execute(status -> {
                var p = parameters(binding); fence(p, binding);
                boolean recovered = queue.recoverExpiredAttemptLease(binding) != null;
                var claim = queue.claimNextAttempt(binding);
                if (claim == null) return new TimedClaim(null, null, null, 0, recovered);
                var snapshot = dispatch.loadForReadRecovery(routeKey, claim);
                long beforeClock = System.nanoTime();
                Instant checkedAt = databaseNow(p);
                if (checkedAt.isBefore(claim.startedAt()) || !checkedAt.isBefore(claim.leaseUntil())) throw unavailable();
                return new TimedClaim(claim, snapshot, checkedAt, beforeClock, recovered);
            }));
        }
    }

    private Step retry(String routeKey, GatewayWorkQueue.Claim claim, String safeCode, Duration delay) {
        if (Thread.currentThread().isInterrupted()) return Step.FENCED_OUT;
        try (var ignored = TenantRequestContext.open(null, claim.binding().organisationId())) {
            Boolean finished = transactions.execute(status -> {
                var p = parameters(claim.binding()); fence(p, claim.binding());
                // Full actual claim equality and active historical binding, not
                // merely queue.complete's token predicate or the caller's hint.
                dispatch.loadForReadRecovery(routeKey, claim);
                Instant checkedAt = databaseNow(p);
                if (checkedAt.isBefore(claim.startedAt()) || !checkedAt.isBefore(claim.leaseUntil())) throw unavailable();
                // Last write in this transaction; failure must roll back.
                if (!queue.complete(claim, GatewayWorkQueue.Outcome.RETRY, safeCode, delay)) throw unavailable();
                return true;
            });
            return Boolean.TRUE.equals(finished) ? Step.RETRY : Step.FENCED_OUT;
        } catch (RuntimeException expiredOrChanged) { return Step.FENCED_OUT; }
    }

    private void fence(MapSqlParameterSource p, GatewayWorkQueue.ProviderBinding binding) {
        if (!TransactionSynchronizationManager.isActualTransactionActive() || TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                || !Boolean.TRUE.equals(jdbc.queryForObject("SELECT current_app_organisation_id()=:organisationId AND current_app_user_id() IS NULL", p, Boolean.class))) throw unavailable();
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(:lockKey,0))",
                p.addValue("lockKey", binding.schoolId() + ":finance"), (r, n) -> true);
    }
    private Instant databaseNow(MapSqlParameterSource p) {
        return Objects.requireNonNull(jdbc.queryForObject("SELECT clock_timestamp()", p,
                (r, n) -> r.getObject(1, java.time.OffsetDateTime.class).toInstant()));
    }
    static Duration available(Instant leaseUntil, Instant checkedAt, long nanoBeforeClockQuery, Duration reserve, Duration cap) {
        Duration left = Duration.between(checkedAt, leaseUntil).minusNanos(System.nanoTime() - nanoBeforeClockQuery).minus(reserve);
        return left.compareTo(cap) > 0 ? cap : left;
    }
    private static Duration normalRetry(GatewayWorkQueue.Claim claim) {
        return Duration.ofSeconds(GatewayWorkQueue.retryDelay(claim.attemptNumber(), claim.token()));
    }
    private static PaymentProviderAdapter.HostedCheckout create(PaymentProviderAdapter provider, PaymentProviderAdapter.CheckoutRequest request, Duration budget) {
        if (Thread.currentThread().isInterrupted()) return null;
        long deadline = System.nanoTime() + budget.toNanos();
        var task = new FutureTask<PaymentProviderAdapter.HostedCheckout>(() -> {
            requireOutsidePersistence();
            return provider.createHostedCheckout(request);
        });
        Thread.ofVirtual().name("school-gateway-checkout-create").start(task);
        try {
            long remaining = deadline - System.nanoTime();
            return remaining > 0 ? task.get(remaining, TimeUnit.NANOSECONDS) : null;
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return null; }
        catch (ExecutionException | TimeoutException unknown) { return null; }
        finally { if (!task.isDone()) task.cancel(true); }
    }
    private static GatewayWorkQueue.ProviderBinding binding(GatewayProviderRegistry.Route route) {
        return new GatewayWorkQueue.ProviderBinding(route.organisationId(), route.schoolId(), route.providerConfigId(), route.providerCode(),
                route.environment().name(), route.merchantAccountReference(), route.credentialsSecretReference());
    }
    private static MapSqlParameterSource parameters(GatewayWorkQueue.ProviderBinding binding) {
        return new MapSqlParameterSource().addValue("organisationId", binding.organisationId()).addValue("schoolId", binding.schoolId());
    }
    private static void requireOutsidePersistence() {
        if (TenantRequestContext.current().isPresent() || TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isSynchronizationActive() || !TransactionSynchronizationManager.getResourceMap().isEmpty()) throw unavailable();
    }
    private static IllegalStateException unavailable() { return new IllegalStateException("Gateway worker requires trusted background admission outside caller persistence"); }
    private record TimedClaim(GatewayWorkQueue.Claim claim, GatewayAttemptDispatch.ReadRecovery snapshot,
            Instant checkedAt, long nanoBeforeClockQuery, boolean recovered) {
        @Override public String toString() { return "TimedClaim[private bounded gateway work]"; }
    }
}
