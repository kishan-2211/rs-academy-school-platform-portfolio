package com.educore.api.finance;

import com.educore.api.tenancy.TenantRequestContext;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * One bounded authenticated inbox lease per invocation. Callback metadata is
 * routing evidence only: all payment facts come from an independent same-ID
 * GET, and only the common transactional finalizer may post financial records.
 * No provider credentials or network calls run under caller/tenant persistence.
 */
@Service
public final class GatewayWebhookWorker {
    private static final Duration GET_RESERVE = Duration.ofSeconds(45);
    private static final Duration MAX_GET = Duration.ofSeconds(40);
    private final GatewayProviderRegistry registry;
    private final NamedParameterJdbcTemplate jdbc;
    private final GatewayWorkQueue queue;
    private final GatewayInboxDispatch dispatch;
    private final GatewayProviderDiscovery discovery;
    private final GatewayObservationFinalizer finalizer;
    private final GatewayRefundInboxRecovery refundRecovery;
    private final TransactionTemplate transactions;
    // Immutable configured route identity fixes the merchant and credentials;
    // operator key rotation requires restart, while each claim rechecks the DB.
    private final ConcurrentHashMap<GatewayProviderRegistry.Route, PaymentProviderAdapter> providers = new ConcurrentHashMap<>();

    public GatewayWebhookWorker(GatewayProviderRegistry registry, NamedParameterJdbcTemplate jdbc,
            GatewayWorkQueue queue, GatewayInboxDispatch dispatch, GatewayProviderDiscovery discovery,
            GatewayObservationFinalizer finalizer, GatewayRefundInboxRecovery refundRecovery,
            PlatformTransactionManager manager, Clock clock) {
        this.registry = Objects.requireNonNull(registry); this.jdbc = Objects.requireNonNull(jdbc);
        this.queue = Objects.requireNonNull(queue); this.dispatch = Objects.requireNonNull(dispatch);
        this.discovery = Objects.requireNonNull(discovery); this.finalizer = Objects.requireNonNull(finalizer);
        this.refundRecovery = Objects.requireNonNull(refundRecovery);
        Objects.requireNonNull(clock); // Clock cannot authorize/extend a database-authored lease.
        transactions = new TransactionTemplate(Objects.requireNonNull(manager));
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transactions.setTimeout(15); transactions.setReadOnly(false);
    }

    public GatewayAttemptWorker.Step runNext(String routeKey) {
        requireOutsidePersistence();
        if (!registry.workerEnabled()) return GatewayAttemptWorker.Step.DISABLED;
        if (Thread.currentThread().isInterrupted()) return GatewayAttemptWorker.Step.FENCED_OUT;
        var route = registry.findRoute(routeKey).orElse(null);
        if (route == null) return GatewayAttemptWorker.Step.UNAVAILABLE;
        if (route.environment() == CashfreePaymentProviderAdapter.Environment.LIVE && !registry.liveEnabled()) {
            return GatewayAttemptWorker.Step.DISABLED;
        }
        var binding = binding(route);
        TimedClaim admitted;
        try { admitted = claim(routeKey, binding); }
        catch (RuntimeException unavailable) { return GatewayAttemptWorker.Step.FENCED_OUT; }
        if (admitted.claim() == null) return admitted.recovered() ? GatewayAttemptWorker.Step.RECOVERED : GatewayAttemptWorker.Step.IDLE;
        var claim = admitted.claim();
        if (Thread.currentThread().isInterrupted()) return GatewayAttemptWorker.Step.FENCED_OUT;
        if ("REFUND_STATUS_WEBHOOK".equals(admitted.admission().eventType())
                || "AUTO_REFUND_STATUS_WEBHOOK".equals(admitted.admission().eventType())) {
            // Only a canonical persisted standard-refund identity may wake its
            // independent GET. Missing/auto-refund association remains review;
            // callback text never becomes successful money or a provider POST.
            if (GatewayRefundInboxRecovery.canRecover(admitted.admission())) {
                PaymentProviderAdapter provider;
                try { provider = providers.computeIfAbsent(route, registry::provider); }
                catch (RuntimeException unavailable) {
                    return finish(routeKey, claim, GatewayWorkQueue.Outcome.RETRY,
                            "GATEWAY_PROVIDER_UNAVAILABLE", normalRetry(claim));
                }
                try { return refundRecovery.recover(provider, routeKey, claim, admitted.admission(), admitted.nanoBeforeAdmission()); }
                catch (RuntimeException unknown) { return GatewayAttemptWorker.Step.FENCED_OUT; }
            }
            return finish(routeKey, claim, GatewayWorkQueue.Outcome.REVIEW_REQUIRED,
                    "GATEWAY_REFUND_RECONCILIATION_REQUIRED", normalRetry(claim));
        }
        Duration budget = GatewayAttemptWorker.available(claim.leaseUntil(), admitted.admission().checkedAt(),
                admitted.nanoBeforeAdmission(), GET_RESERVE, MAX_GET);
        if (!budget.isPositive()) return finish(routeKey, claim, GatewayWorkQueue.Outcome.RETRY,
                "GATEWAY_INBOX_READ_BUDGET_UNAVAILABLE", normalRetry(claim));
        PaymentProviderAdapter provider;
        try { provider = providers.computeIfAbsent(route, registry::provider); }
        catch (RuntimeException unavailable) {
            return finish(routeKey, claim, GatewayWorkQueue.Outcome.RETRY, "GATEWAY_PROVIDER_UNAVAILABLE", normalRetry(claim));
        }
        if (Thread.currentThread().isInterrupted()) return GatewayAttemptWorker.Step.FENCED_OUT;
        // Credential construction may take time. Never give GET a fresh budget
        // after it, or after a database lock/commit wait.
        budget = GatewayAttemptWorker.available(claim.leaseUntil(), admitted.admission().checkedAt(),
                admitted.nanoBeforeAdmission(), GET_RESERVE, MAX_GET);
        if (!budget.isPositive()) return finish(routeKey, claim, GatewayWorkQueue.Outcome.RETRY,
                "GATEWAY_INBOX_READ_BUDGET_UNAVAILABLE", normalRetry(claim));
        GatewayProviderDiscovery.ReadResult read;
        try {
            read = admitted.admission().attempt().isPresent()
                    ? discovery.read(provider, admitted.admission().attempt().orElseThrow(), budget)
                    : discovery.readOrder(provider, binding, admitted.admission().orderReference(), budget);
        } catch (RuntimeException unknown) {
            return finish(routeKey, claim, GatewayWorkQueue.Outcome.RETRY, "GATEWAY_INBOX_READ_OUTCOME_UNKNOWN", normalRetry(claim));
        }
        try {
            // A partial read can carry real captured money even when shutdown
            // interrupts the controlling thread. Let the fact-first boundary
            // retain it; that boundary forbids financial completion on interrupt.
            var outcome = finalizer.finalizeInboxObservation(routeKey, claim, read.evidence());
            if (Thread.currentThread().isInterrupted()) return GatewayAttemptWorker.Step.FENCED_OUT;
            return switch (outcome) {
                case RETRY -> GatewayAttemptWorker.Step.RETRY;
                case COMPLETE -> GatewayAttemptWorker.Step.COMPLETE;
                case REVIEW_REQUIRED -> GatewayAttemptWorker.Step.REVIEW_REQUIRED;
            };
        } catch (RuntimeException unknown) {
            // Retained GET facts may already be committed. Leave actual lease
            // expiry/recovery intact rather than guessing a second completion.
            return GatewayAttemptWorker.Step.FENCED_OUT;
        }
    }

    private TimedClaim claim(String routeKey, GatewayWorkQueue.ProviderBinding binding) {
        long beforeAdmission = System.nanoTime();
        try (var ignored = TenantRequestContext.open(null, binding.organisationId())) {
            return Objects.requireNonNull(transactions.execute(status -> {
                var p = parameters(binding); fence(p, binding);
                boolean recovered = queue.recoverExpiredWebhookLease(binding) != null;
                var claim = queue.claimNextWebhook(binding);
                if (claim == null) return new TimedClaim(null, null, beforeAdmission, recovered);
                var admission = Objects.requireNonNull(dispatch.loadForReadRecovery(routeKey, claim));
                if (admission.checkedAt().isBefore(claim.startedAt()) || !admission.checkedAt().isBefore(claim.leaseUntil())) throw unavailable();
                return new TimedClaim(claim, admission, beforeAdmission, recovered);
            }));
        }
    }

    private GatewayAttemptWorker.Step finish(String routeKey, GatewayWorkQueue.Claim claim,
            GatewayWorkQueue.Outcome outcome, String safeCode, Duration delay) {
        if (Thread.currentThread().isInterrupted()) return GatewayAttemptWorker.Step.FENCED_OUT;
        try (var ignored = TenantRequestContext.open(null, claim.binding().organisationId())) {
            Boolean finished = transactions.execute(status -> {
                var p = parameters(claim.binding()); fence(p, claim.binding());
                // loadForReadRecovery verifies the full current persisted lease,
                // not merely complete's token predicate or callback success text.
                dispatch.loadForReadRecovery(routeKey, claim);
                Instant checkedAt = Objects.requireNonNull(jdbc.queryForObject("SELECT clock_timestamp()", p,
                        (r, n) -> r.getObject(1, java.time.OffsetDateTime.class).toInstant()));
                if (checkedAt.isBefore(claim.startedAt()) || !checkedAt.isBefore(claim.leaseUntil())) throw unavailable();
                if (!queue.complete(claim, outcome, safeCode, delay)) throw unavailable();
                return true; // Completion is the last write; false rolls back.
            });
            if (!Boolean.TRUE.equals(finished)) return GatewayAttemptWorker.Step.FENCED_OUT;
            return outcome == GatewayWorkQueue.Outcome.REVIEW_REQUIRED
                    ? GatewayAttemptWorker.Step.REVIEW_REQUIRED : GatewayAttemptWorker.Step.RETRY;
        } catch (RuntimeException expiredOrChanged) { return GatewayAttemptWorker.Step.FENCED_OUT; }
    }

    private void fence(MapSqlParameterSource p, GatewayWorkQueue.ProviderBinding binding) {
        if (!TransactionSynchronizationManager.isActualTransactionActive() || TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                || !Boolean.TRUE.equals(jdbc.queryForObject("SELECT current_app_organisation_id()=:organisationId AND current_app_user_id() IS NULL", p, Boolean.class))) throw unavailable();
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(:lockKey,0))",
                p.addValue("lockKey", binding.schoolId() + ":finance"), (r, n) -> true);
    }
    private static Duration normalRetry(GatewayWorkQueue.Claim claim) {
        return Duration.ofSeconds(GatewayWorkQueue.retryDelay(claim.attemptNumber(), claim.token()));
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
    private static IllegalStateException unavailable() {
        return new IllegalStateException("Gateway inbox worker requires trusted background admission outside caller persistence");
    }
    private record TimedClaim(GatewayWorkQueue.Claim claim, GatewayInboxDispatch.Admission admission,
            long nanoBeforeAdmission, boolean recovered) {
        @Override public String toString() { return "TimedClaim[private bounded callback work]"; }
    }
}
