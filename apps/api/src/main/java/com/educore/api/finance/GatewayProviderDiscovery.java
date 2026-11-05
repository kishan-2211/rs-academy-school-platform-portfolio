package com.educore.api.finance;

import com.educore.api.tenancy.TenantRequestContext;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Bounded, GET-only provider I/O between committed admission and the existing
 * transactional finalizer. This is not a scheduler, public API or financial
 * authority. The internal caller must supply the registry-created adapter for
 * the exact admitted route; providerCode alone is not merchant authentication.
 * No tenant scope or transaction may surround network calls. Unknown or unread
 * orders remain explicit and never become an invented empty payment list.
 */
@Service
public final class GatewayProviderDiscovery {
    private static final Duration MAX_BUDGET = Duration.ofSeconds(60);
    private final Clock clock;

    public GatewayProviderDiscovery(Clock clock) {
        this.clock = Objects.requireNonNull(clock);
    }

    /** Internal sensitive result, never an HTTP DTO or permission to follow its URL. */
    public record ReadResult(GatewayDiscoveryEvidence.Discovery evidence,
            Optional<PaymentProviderAdapter.HostedCheckout> hostedCheckout) {
        public ReadResult {
            Objects.requireNonNull(evidence);
            Objects.requireNonNull(hostedCheckout);
        }

        @Override public String toString() { return "ReadResult[internal bounded gateway GET evidence]"; }
    }

    /**
     * One monotonic budget covers the entire lookup (including its internal GETs)
     * and all subsequent order reads. A caller must reserve additional lease time
     * for financial finalization, which independently checks real database time.
     * Expired leases do not discard genuinely observed money, but cannot post it.
     */
    public ReadResult read(PaymentProviderAdapter provider, GatewayAttemptDispatch.ReadRecovery attempt,
            Duration budget) {
        if (provider == null || attempt == null || budget == null || budget.isNegative() || budget.isZero()
                || budget.compareTo(MAX_BUDGET) > 0) {
            throw new IllegalArgumentException("Bounded internal gateway GET request required");
        }
        requireOutsidePersistence();
        // Start before inspecting the adapter: never reset the budget per order.
        long deadline = System.nanoTime() + budget.toNanos();
        try {
            if (!attempt.binding().providerCode().equals(provider.providerCode())) throw unavailable();
        } catch (RuntimeException failure) {
            throw unavailable(); // No provider payload, references or sensitive cause.
        }
        if (Thread.currentThread().isInterrupted()) return unknown(attempt);
        var lookup = invoke(() -> provider.lookupHostedCheckout(attempt.checkoutReference().toString()), deadline);
        if (lookup.value() == null) return unknown(attempt);
        var observed = lookup.value().observation();
        var expected = new GatewayDiscoveryEvidence.ExpectedAttempt(attempt.attemptId(), attempt.accountId(),
                attempt.binding(), attempt.amount(), attempt.currencyCode(), attempt.createdAt(),
                attempt.expiresAt(), attempt.schoolTimeZone());
        var initial = new GatewayDiscoveryEvidence.Discovery(attempt.binding(), observed, List.of(), clock.instant());
        // Reuse exact association/amount/expiry/bounds validation before fetching
        // any listed order. Economic contradictions still retain their real facts;
        // foreign or malformed checkout identities never authorize more GETs.
        boolean associated;
        try { associated = GatewayDiscoveryEvidence.classify(expected, initial).checkout().isPresent(); }
        catch (RuntimeException invalid) { associated = false; }
        if (!associated) return new ReadResult(initial, Optional.empty());

        var reads = new ArrayList<GatewayDiscoveryEvidence.OrderRead>();
        boolean stop = false;
        for (String order : observed.orderReferences()) {
            if (stop || Thread.currentThread().isInterrupted() || deadline - System.nanoTime() <= 0) {
                reads.add(GatewayDiscoveryEvidence.OrderRead.unknown(order));
                stop = true;
                continue;
            }
            var response = invoke(() -> provider.fetchPayments(order), deadline);
            stop = response.stop();
            if (response.value() == null) {
                reads.add(GatewayDiscoveryEvidence.OrderRead.unknown(order));
                continue;
            }
            try { reads.add(GatewayDiscoveryEvidence.OrderRead.complete(order, response.value())); }
            catch (RuntimeException invalid) {
                // A failed safety bound is UNKNOWN, not a truncated complete list.
                reads.add(GatewayDiscoveryEvidence.OrderRead.unknown(order));
            }
        }
        return new ReadResult(new GatewayDiscoveryEvidence.Discovery(attempt.binding(), observed, reads,
                clock.instant()), lookup.value().hostedCheckout());
    }

    private ReadResult unknown(GatewayAttemptDispatch.ReadRecovery attempt) {
        return new ReadResult(new GatewayDiscoveryEvidence.Discovery(attempt.binding(), null, List.of(),
                clock.instant()), Optional.empty());
    }

    /**
     * Authenticated inbox routing may lack a checkout reference. Observe only
     * that persisted provider order; no guessed learner/attempt or synthetic
     * complete checkout is introduced. These facts remain unassociated until
     * a separate full same-ID checkout GET establishes financial eligibility.
     */
    public ReadResult readOrder(PaymentProviderAdapter provider, GatewayWorkQueue.ProviderBinding binding,
            String orderReference, Duration budget) {
        if (provider == null || binding == null || budget == null || !budget.isPositive()
                || budget.compareTo(MAX_BUDGET) > 0) throw unavailable();
        requireOutsidePersistence();
        long deadline = System.nanoTime() + budget.toNanos();
        try {
            PaymentProviderAdapter.requireReference(orderReference);
            if (!binding.providerCode().equals(provider.providerCode())) throw unavailable();
        } catch (RuntimeException invalid) { throw unavailable(); }
        var response = invoke(() -> provider.fetchPayments(orderReference), deadline);
        GatewayDiscoveryEvidence.OrderRead read;
        try {
            read = response.value() == null ? GatewayDiscoveryEvidence.OrderRead.unknown(orderReference)
                    : GatewayDiscoveryEvidence.OrderRead.complete(orderReference, response.value());
        } catch (RuntimeException invalid) {
            read = GatewayDiscoveryEvidence.OrderRead.unknown(orderReference);
        }
        return new ReadResult(new GatewayDiscoveryEvidence.Discovery(binding, null, List.of(read), clock.instant()), Optional.empty());
    }

    /**
     * GET-only recovery of the exact persisted, independently approved refund.
     * The internal caller must use the registry-created adapter for this frozen
     * route, commit admission before I/O and later retain this read identity with
     * GatewayRefundReadStore. Neither a matching provider code nor this result
     * grants dispatch, lease, completion, fee-posting or budget-release authority.
     */
    RefundRead readRefund(PaymentProviderAdapter provider, GatewayRefundReadStore.Prepared prepared,
            UUID readId, Duration budget) {
        if (provider == null || prepared == null || readId == null || budget == null
                || !budget.isPositive() || budget.compareTo(MAX_BUDGET) > 0) throw unavailable();
        requireOutsidePersistence();
        long deadline = System.nanoTime() + budget.toNanos();
        var expected = prepared.expected();
        try {
            if (!expected.binding().providerCode().equals(provider.providerCode())
                    || clock.instant().isBefore(expected.approvedAt())) throw unavailable();
        } catch (RuntimeException invalid) { throw unavailable(); }
        var response = invoke(() -> provider.fetchRefund(expected.orderReference(), expected.refundId().toString()), deadline);
        // Keep contradictory observations for the existing verifier/review path.
        // A timeout, null or exception remains UNKNOWN, not provider failure and
        // never permission to repeat POST or release an encumbered refund budget.
        return new RefundRead(prepared, readId, response.value());
    }

    record RefundRead(GatewayRefundReadStore.Prepared prepared, UUID readId,
            PaymentProviderAdapter.RefundObservation facts) {
        RefundRead { Objects.requireNonNull(prepared); Objects.requireNonNull(readId); }
        @Override public String toString() { return "RefundRead[private independent same-ID GET; not completion]"; }
    }

    private static <T> CallResult<T> invoke(Callable<T> operation, long deadline) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0 || Thread.currentThread().isInterrupted()) return new CallResult<>(null, true);
        // Separate virtual call is also needed for a multi-request adapter method.
        // Never close/join an executor here: an uncooperative call must not make
        // the controlling worker exceed its deadline. The trusted HTTP adapter
        // handles cancellation, bounded bodies and response-stream cleanup.
        var task = new FutureTask<CallResult<T>>(() -> {
            requireOutsidePersistence();
            try {
                T value = operation.call();
                return Thread.currentThread().isInterrupted()
                        ? new CallResult<>(null, true) : new CallResult<>(value, false);
            } catch (Exception failed) {
                // An adapter may restore its own interrupted flag and throw an
                // opaque UNKNOWN. Do not start more I/O after that cancellation;
                // it does not imply that the controlling thread was interrupted.
                return new CallResult<>(null, Thread.currentThread().isInterrupted());
            }
        });
        Thread.ofVirtual().name("school-gateway-provider-read").start(task);
        try {
            remaining = deadline - System.nanoTime();
            if (remaining <= 0) return new CallResult<>(null, true);
            return task.get(remaining, TimeUnit.NANOSECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new CallResult<>(null, true);
        } catch (TimeoutException timedOut) {
            return new CallResult<>(null, true);
        } catch (ExecutionException failed) {
            // Unexpected task failure stops this scan. Ordinary adapter failures
            // were classified inside the task; no private cause escapes either.
            return new CallResult<>(null, true);
        } finally {
            if (!task.isDone()) task.cancel(true);
        }
    }

    private static void requireOutsidePersistence() {
        if (TenantRequestContext.current().isPresent() || TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isSynchronizationActive()
                || !TransactionSynchronizationManager.getResourceMap().isEmpty()) throw unavailable();
    }

    private static IllegalStateException unavailable() {
        return new IllegalStateException("Gateway GET requires trusted admission outside tenant persistence");
    }

    private record CallResult<T>(T value, boolean stop) {}
}
