package com.educore.api.finance;

import static com.educore.api.finance.GatewayDiscoveryEvidence.Disposition.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.educore.api.finance.PaymentProviderAdapter.CheckoutObservation;
import com.educore.api.finance.PaymentProviderAdapter.CheckoutStatus;
import com.educore.api.finance.PaymentProviderAdapter.HostedCheckout;
import com.educore.api.finance.PaymentProviderAdapter.HostedCheckoutLookup;
import com.educore.api.finance.PaymentProviderAdapter.PaymentObservation;
import com.educore.api.finance.PaymentProviderAdapter.PaymentStatus;
import com.educore.api.tenancy.TenantRequestContext;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Stubbed provider protocol only: no database, merchant network, posting, or checkout creation. */
class GatewayProviderDiscoveryTest {
    private static final UUID ATTEMPT = UUID.fromString("00000000-0000-4000-8000-000000000801");
    private static final UUID ACCOUNT = UUID.fromString("00000000-0000-4000-8000-000000000802");
    private static final UUID ORGANISATION = UUID.fromString("00000000-0000-4000-8000-000000000803");
    private static final UUID SCHOOL = UUID.fromString("00000000-0000-4000-8000-000000000804");
    private static final UUID PROVIDER_CONFIG = UUID.fromString("00000000-0000-4000-8000-000000000805");
    private static final BigDecimal AMOUNT = new BigDecimal("1000.00");
    private static final Instant NOW = Instant.parse("2026-10-13T08:32:00.987654321Z");
    private static final Instant CREATED = Instant.parse("2026-10-13T08:00:00.123456Z");
    private static final Instant EXPIRES = Instant.parse("2026-10-13T08:30:00Z");
    private static final Instant PAID = Instant.parse("2026-10-13T08:31:59.123456789Z");
    private static final String ORDER = "synthetic_runner_order_001";
    private static final String OTHER_ORDER = "synthetic_runner_order_002";
    private static final String LAST_ORDER = "synthetic_runner_order_003";
    private static final String PAYMENT = "synthetic_runner_payment_001";
    private static final URI HOSTED = URI.create("https://payments-test.cashfree.com/links/synthetic_runner");
    private static final Duration NORMAL_BUDGET = Duration.ofSeconds(5);
    private final GatewayProviderDiscovery service = new GatewayProviderDiscovery(Clock.fixed(NOW, ZoneOffset.UTC));

    @AfterEach void clearOnlyTestState() {
        TransactionSynchronizationManager.clear();
        assertThat(TenantRequestContext.current()).isEmpty();
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
    }

    @Test void completeSingleGetScanPreservesExactOriginalNanosAndHostedLookupWithoutMutation() {
        var provider = provider(paidCheckout(List.of(ORDER)));
        when(provider.fetchPayments(ORDER)).thenAnswer(call -> {
            assertNoAmbientAuthority();
            return List.of(success());
        });

        var read = service.read(provider, attempt(), NORMAL_BUDGET);
        var classified = classify(read);
        assertThat(read.evidence().binding()).isEqualTo(binding());
        assertThat(read.evidence().observedAt()).isEqualTo(NOW);
        assertThat(read.hostedCheckout()).contains(new HostedCheckout(ATTEMPT.toString(), HOSTED));
        assertThat(classified.discoveryComplete()).isTrue();
        assertThat(classified.disposition()).isEqualTo(EXACT_CAPTURE);
        assertThat(classified.paymentFacts()).containsExactly(success());
        assertThat(classified.exactCapture()).hasValueSatisfying(candidate -> {
            assertThat(candidate.payment().providerPaidAt()).isEqualTo(PAID);
            assertThat(candidate.payment().providerPaidAt().getNano()).isEqualTo(123456789);
            assertThat(candidate.originalBusinessDate()).isEqualTo(LocalDate.of(2026, 10, 13));
        });
        verify(provider, times(1)).lookupHostedCheckout(ATTEMPT.toString());
        verify(provider, times(1)).fetchPayments(ORDER);
        assertReadOnly(provider);
    }

    @Test void unassociatedOrderReadPreservesExactMoneyWithoutManufacturingCheckoutOrChildIdentity() {
        var provider = provider(paidCheckout(List.of(ORDER)));
        when(provider.fetchPayments(ORDER)).thenAnswer(call -> { assertNoAmbientAuthority(); return List.of(success()); });
        var read = service.readOrder(provider, binding(), ORDER, NORMAL_BUDGET);
        assertThat(read.evidence().binding()).isEqualTo(binding());
        assertThat(read.evidence().checkout()).isNull();
        assertThat(read.evidence().observedAt()).isEqualTo(NOW);
        assertThat(read.evidence().orderReads()).hasSize(1);
        assertThat(read.evidence().orderReads().getFirst().payments()).contains(List.of(success()));
        assertThat(read.hostedCheckout()).isEmpty();
        verify(provider, never()).lookupHostedCheckout(anyString()); assertReadOnly(provider);
    }

    @Test void unassociatedFailedNullAndOversizedOrderReadsRemainUnknownNotEmptyProof() {
        for (int mode = 0; mode < 3; mode++) {
            var provider = provider(paidCheckout(List.of(ORDER)));
            if (mode == 0) when(provider.fetchPayments(ORDER)).thenThrow(new IllegalStateException("synthetic-private-response"));
            else if (mode == 1) when(provider.fetchPayments(ORDER)).thenReturn(null);
            else when(provider.fetchPayments(ORDER)).thenReturn(java.util.Collections.nCopies(101, success()));
            var read = service.readOrder(provider, binding(), ORDER, NORMAL_BUDGET);
            assertThat(read.evidence().checkout()).isNull();
            assertThat(read.evidence().orderReads().getFirst().payments()).isEmpty();
            assertThat(read.hostedCheckout()).isEmpty(); assertReadOnly(provider);
        }
    }

    @Test void unassociatedOrderRequiresCanonicalReferenceBoundedBudgetAndNoAmbientPersistence() {
        var provider = provider(paidCheckout(List.of(ORDER)));
        for (String order : List.of("", "synthetic_private/order", "x".repeat(101))) {
            assertThatThrownBy(() -> service.readOrder(provider, binding(), order, NORMAL_BUDGET)).isInstanceOf(RuntimeException.class).hasNoCause();
        }
        for (Duration budget : List.of(Duration.ZERO, Duration.ofSeconds(-1), Duration.ofSeconds(61))) {
            assertThatThrownBy(() -> service.readOrder(provider, binding(), ORDER, budget)).isInstanceOf(RuntimeException.class).hasNoCause();
        }
        TransactionSynchronizationManager.initSynchronization();
        try { assertThatThrownBy(() -> service.readOrder(provider, binding(), ORDER, NORMAL_BUDGET)).isInstanceOf(RuntimeException.class); }
        finally { TransactionSynchronizationManager.clearSynchronization(); }
        verify(provider, never()).fetchPayments(anyString()); verify(provider, never()).lookupHostedCheckout(anyString());
    }

    @Test void terminalZeroRequiresSuccessfullyReadEmptyPaymentsAndNeverInventsAHostedRedirect() {
        for (CheckoutStatus status : List.of(CheckoutStatus.CANCELLED, CheckoutStatus.EXPIRED)) {
            var checkout = checkout(ATTEMPT.toString(), ATTEMPT.toString(), AMOUNT, "INR", BigDecimal.ZERO,
                    status, EXPIRES, List.of(ORDER));
            var provider = providerWithoutHosted(checkout);
            when(provider.fetchPayments(ORDER)).thenReturn(List.of());
            var read = service.read(provider, attempt(), NORMAL_BUDGET);
            assertThat(read.hostedCheckout()).isEmpty();
            assertThat(classify(read).disposition()).isEqualTo(TERMINAL_ZERO_OBSERVED);
            assertThat(classify(read).discoveryComplete()).isTrue();
            assertThat(classify(read).exactCapture()).isEmpty();
            verify(provider, times(1)).fetchPayments(ORDER);
            assertReadOnly(provider);
        }
    }

    @Test void createdCheckoutWithoutOrdersIsPendingRatherThanTerminalZero() {
        var provider = provider(checkout(ATTEMPT.toString(), ATTEMPT.toString(), AMOUNT, "INR", BigDecimal.ZERO,
                CheckoutStatus.CREATED, EXPIRES, List.of()));
        var read = service.read(provider, attempt(), NORMAL_BUDGET);
        assertThat(classify(read).disposition()).isEqualTo(PENDING);
        assertThat(classify(read).discoveryComplete()).isTrue();
        verify(provider, never()).fetchPayments(anyString());
        assertReadOnly(provider);
    }

    @Test void rejectsForeignIdentityMoneyCurrencyExpiryAndInvalidOrderListsBeforePaymentGet() {
        var excessive = IntStream.range(0, 101).mapToObj(index -> "synthetic_order_" + index).toList();
        var cases = List.of(
                checkout(ACCOUNT.toString(), ATTEMPT.toString(), AMOUNT, "INR", AMOUNT, CheckoutStatus.PAID, EXPIRES, List.of(ORDER)),
                checkout(ATTEMPT.toString(), ACCOUNT.toString(), AMOUNT, "INR", AMOUNT, CheckoutStatus.PAID, EXPIRES, List.of(ORDER)),
                checkout(ATTEMPT.toString(), ATTEMPT.toString(), new BigDecimal("1000.01"), "INR", AMOUNT, CheckoutStatus.PAID, EXPIRES, List.of(ORDER)),
                checkout(ATTEMPT.toString(), ATTEMPT.toString(), AMOUNT, "USD", AMOUNT, CheckoutStatus.PAID, EXPIRES, List.of(ORDER)),
                checkout(ATTEMPT.toString(), ATTEMPT.toString(), AMOUNT, "INR", AMOUNT, CheckoutStatus.PAID, EXPIRES.plusNanos(1), List.of(ORDER)),
                paidCheckout(excessive), paidCheckout(List.of(ORDER, ORDER)), paidCheckout(List.of("../foreign")));
        for (var checkout : cases) {
            var provider = provider(checkout);
            var read = service.read(provider, attempt(), NORMAL_BUDGET);
            assertThat(classify(read).exactCapture()).isEmpty();
            assertThat(classify(read).discoveryComplete()).isFalse();
            assertThat(read.hostedCheckout()).isEmpty();
            verify(provider, never()).fetchPayments(anyString());
            assertReadOnly(provider);
        }
    }

    @Test void nullAndFailedCheckoutLookupsRemainUnknownWithoutLeakingPayloadOrTryingAnotherMethod() {
        for (boolean failure : List.of(false, true)) {
            var provider = provider(paidCheckout(List.of(ORDER)));
            if (failure) when(provider.lookupHostedCheckout(ATTEMPT.toString()))
                    .thenThrow(new IllegalStateException("synthetic-private-provider-body"));
            else when(provider.lookupHostedCheckout(ATTEMPT.toString())).thenReturn(null);
            var read = service.read(provider, attempt(), NORMAL_BUDGET);
            assertThat(read.evidence().checkout()).isNull();
            assertThat(read.evidence().orderReads()).isEmpty();
            assertThat(read.hostedCheckout()).isEmpty();
            assertThat(classify(read).disposition()).isEqualTo(INCOMPLETE);
            assertThat(read.toString()).doesNotContain("synthetic-private-provider-body", ATTEMPT.toString(), HOSTED.toString());
            verify(provider, times(1)).lookupHostedCheckout(ATTEMPT.toString());
            verify(provider, never()).fetchPayments(anyString());
            assertReadOnly(provider);
        }
    }

    @Test void oneOrdinaryOrderFailureDoesNotDiscardALaterSuccessfullyObservedPayment() {
        var provider = provider(paidCheckout(List.of(ORDER, OTHER_ORDER)));
        when(provider.fetchPayments(ORDER)).thenThrow(new IllegalStateException("synthetic-private-order-body"));
        var success = success(OTHER_ORDER, PAYMENT);
        when(provider.fetchPayments(OTHER_ORDER)).thenReturn(List.of(success));

        var read = service.read(provider, attempt(), NORMAL_BUDGET);
        assertThat(read.evidence().orderReads()).extracting(GatewayDiscoveryEvidence.OrderRead::orderReference)
                .containsExactly(ORDER, OTHER_ORDER);
        assertThat(read.evidence().orderReads().getFirst().payments()).isEmpty();
        assertThat(read.evidence().orderReads().getLast().payments()).contains(List.of(success));
        assertThat(classify(read).disposition()).isEqualTo(INCOMPLETE);
        assertThat(classify(read).paymentFacts()).containsExactly(success);
        assertThat(classify(read).successfulPaymentCount()).isEqualTo(1);
        assertThat(classify(read).exactCapture()).isEmpty();
        assertThat(read.toString()).doesNotContain("synthetic-private-order-body", ORDER, OTHER_ORDER, PAYMENT);
        verify(provider, times(1)).fetchPayments(ORDER);
        verify(provider, times(1)).fetchPayments(OTHER_ORDER);
        assertReadOnly(provider);
    }

    @Test void nullPaymentListIsUnknownRatherThanACompleteEmptyGetAndLaterOrdersStillRun() {
        var provider = provider(paidCheckout(List.of(ORDER, OTHER_ORDER)));
        when(provider.fetchPayments(ORDER)).thenReturn(null);
        when(provider.fetchPayments(OTHER_ORDER)).thenReturn(List.of(success(OTHER_ORDER, PAYMENT)));
        var read = service.read(provider, attempt(), NORMAL_BUDGET);
        assertThat(read.evidence().orderReads().getFirst().payments()).isEmpty();
        assertThat(classify(read).disposition()).isEqualTo(INCOMPLETE);
        assertThat(classify(read).successfulPaymentCount()).isEqualTo(1);
        verify(provider).fetchPayments(OTHER_ORDER);
        assertReadOnly(provider);
    }

    @Test void exactlyOneHundredUntruncatedPaymentsRemainCompleteAndCapturedFactIsPreserved() {
        var provider = provider(paidCheckout(List.of(ORDER)));
        var payments = new ArrayList<PaymentObservation>();
        payments.add(success());
        for (int index = 0; index < 99; index++) payments.add(failed(ORDER, "synthetic_failed_" + index));
        when(provider.fetchPayments(ORDER)).thenReturn(List.copyOf(payments));
        var read = service.read(provider, attempt(), NORMAL_BUDGET);
        assertThat(read.evidence().orderReads().getFirst().payments()).contains(payments);
        assertThat(classify(read).paymentFacts()).hasSize(100);
        assertThat(classify(read).discoveryComplete()).isTrue();
        assertThat(classify(read).disposition()).isEqualTo(EXACT_CAPTURE);
        assertReadOnly(provider);
    }

    @Test void excessiveOrNullMemberPaymentListsAreUnknownAndNeverTruncatedIntoProof() {
        var excessive = IntStream.range(0, 101).mapToObj(index -> failed(ORDER, "synthetic_failed_" + index)).toList();
        for (List<PaymentObservation> payments : List.of(excessive, Arrays.asList(success(), null))) {
            var provider = provider(paidCheckout(List.of(ORDER)));
            when(provider.fetchPayments(ORDER)).thenReturn(payments);
            var read = service.read(provider, attempt(), NORMAL_BUDGET);
            assertThat(read.evidence().orderReads().getFirst().payments()).isEmpty();
            assertThat(classify(read).discoveryComplete()).isFalse();
            assertThat(classify(read).exactCapture()).isEmpty();
            assertReadOnly(provider);
        }
    }

    @Test void duplicateOrForeignPaymentRowsCannotBecomeCompleteFinancialEvidence() {
        for (var payments : List.of(List.of(success(), success()), List.of(success(OTHER_ORDER, PAYMENT)))) {
            var provider = provider(paidCheckout(List.of(ORDER)));
            when(provider.fetchPayments(ORDER)).thenReturn(payments);
            var read = service.read(provider, attempt(), NORMAL_BUDGET);
            assertThat(classify(read).discoveryComplete()).isFalse();
            assertThat(classify(read).exactCapture()).isEmpty();
            assertReadOnly(provider);
        }
    }

    @Test void laterScanDoesNotFabricateCompletenessByCombiningPreviouslySuccessfulOrderReads() {
        var provider = provider(paidCheckout(List.of(ORDER, OTHER_ORDER)));
        when(provider.fetchPayments(ORDER)).thenReturn(List.of(success())).thenThrow(new IllegalStateException("synthetic-order-unknown"));
        when(provider.fetchPayments(OTHER_ORDER)).thenThrow(new IllegalStateException("synthetic-order-unknown"))
                .thenReturn(List.of(failed(OTHER_ORDER, "synthetic_failed_002")));
        var first = service.read(provider, attempt(), NORMAL_BUDGET);
        var second = service.read(provider, attempt(), NORMAL_BUDGET);
        assertThat(classify(first).discoveryComplete()).isFalse();
        assertThat(classify(first).paymentFacts()).containsExactly(success());
        assertThat(classify(second).discoveryComplete()).isFalse();
        assertThat(classify(second).successfulPaymentCount()).isZero();
        assertThat(second.evidence().orderReads().getFirst().payments()).isEmpty();
        verify(provider, times(2)).lookupHostedCheckout(ATTEMPT.toString());
        assertReadOnly(provider);
    }

    @Test void totalMonotonicTimeoutCancelsBlockedLookupWithoutAnySubsequentProviderCall() throws Exception {
        var provider = provider(paidCheckout(List.of(ORDER)));
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        when(provider.lookupHostedCheckout(ATTEMPT.toString())).thenAnswer(call -> {
            entered.countDown();
            return blocking(release, interrupted, new HostedCheckoutLookup(paidCheckout(List.of(ORDER)), Optional.of(new HostedCheckout(ATTEMPT.toString(), HOSTED))));
        });
        try {
            long started = System.nanoTime();
            var read = service.read(provider, attempt(), Duration.ofMillis(350));
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(interrupted.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(read.evidence().checkout()).isNull();
            assertThat(read.hostedCheckout()).isEmpty();
            assertThat(classify(read).disposition()).isEqualTo(INCOMPLETE);
            verify(provider, never()).fetchPayments(anyString());
            assertReadOnly(provider);
        } finally { release.countDown(); }
    }

    @Test void timeoutDuringPaymentsRetainsEarlierSuccessAndMakesAllUnreadOrdersExplicitlyUnknown() throws Exception {
        var provider = provider(paidCheckout(List.of(ORDER, OTHER_ORDER, LAST_ORDER)));
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        when(provider.fetchPayments(ORDER)).thenReturn(List.of(success()));
        when(provider.fetchPayments(OTHER_ORDER)).thenAnswer(call -> {
            entered.countDown();
            return blocking(release, interrupted, List.of(failed(OTHER_ORDER, "synthetic_failed_002")));
        });
        try {
            var read = service.read(provider, attempt(), Duration.ofMillis(350));
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(interrupted.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(read.evidence().orderReads()).extracting(GatewayDiscoveryEvidence.OrderRead::orderReference)
                    .containsExactly(ORDER, OTHER_ORDER, LAST_ORDER);
            assertThat(read.evidence().orderReads().get(1).payments()).isEmpty();
            assertThat(read.evidence().orderReads().get(2).payments()).isEmpty();
            assertThat(classify(read).paymentFacts()).containsExactly(success());
            assertThat(classify(read).discoveryComplete()).isFalse();
            assertThat(classify(read).exactCapture()).isEmpty();
            verify(provider, never()).fetchPayments(LAST_ORDER);
            assertReadOnly(provider);
        } finally { release.countDown(); }
    }

    @Test void callerInterruptionCancelsInFlightGetPreservesItsFlagAndDoesNotStartAnotherOrder() throws Exception {
        var provider = provider(paidCheckout(List.of(ORDER, OTHER_ORDER)));
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        var flag = new AtomicBoolean();
        var failure = new AtomicReference<Throwable>();
        var result = new AtomicReference<GatewayProviderDiscovery.ReadResult>();
        when(provider.fetchPayments(ORDER)).thenAnswer(call -> {
            entered.countDown();
            return blocking(release, interrupted, List.of(success()));
        });
        Thread caller = Thread.ofVirtual().name("synthetic-discovery-interruption-test").unstarted(() -> {
            try { result.set(service.read(provider, attempt(), NORMAL_BUDGET)); }
            catch (RuntimeException rejected) { failure.set(rejected); }
            finally { flag.set(Thread.currentThread().isInterrupted()); }
        });
        try {
            caller.start();
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            caller.interrupt();
            caller.join(Duration.ofSeconds(3));
            assertThat(caller.isAlive()).isFalse();
            assertThat(flag).isTrue();
            assertThat(interrupted.await(2, TimeUnit.SECONDS)).isTrue();
            if (result.get() != null) {
                assertThat(classify(result.get()).discoveryComplete()).isFalse();
                assertThat(classify(result.get()).exactCapture()).isEmpty();
            } else {
                assertThat(failure.get()).isNotNull();
                assertThat(failure.get().getMessage()).doesNotContain(ORDER, PAYMENT, HOSTED.toString());
            }
            verify(provider, never()).fetchPayments(OTHER_ORDER);
            assertReadOnly(provider);
        } finally { release.countDown(); caller.interrupt(); caller.join(Duration.ofSeconds(3)); }
    }

    @Test void alreadyInterruptedCallerDoesNotPerformIoAndItsFlagIsNotCleared() {
        var provider = provider(paidCheckout(List.of(ORDER)));
        Thread.currentThread().interrupt();
        try {
            try { service.read(provider, attempt(), NORMAL_BUDGET); }
            catch (IllegalStateException expectedRejection) { /* Rejection or incomplete evidence is safe; no I/O is allowed. */ }
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            verify(provider, never()).lookupHostedCheckout(anyString());
            verify(provider, never()).fetchPayments(anyString());
        } finally { Thread.interrupted(); }
    }

    @Test void ambientHumanAndSystemTenantContextsAreRejectedWithoutProviderIoOrContextReplacement() {
        for (UUID actor : Arrays.asList(ACCOUNT, null)) {
            var provider = provider(paidCheckout(List.of(ORDER)));
            try (var ignored = TenantRequestContext.open(actor, ORGANISATION)) {
                var before = TenantRequestContext.current().orElseThrow();
                assertThatThrownBy(() -> service.read(provider, attempt(), NORMAL_BUDGET)).isInstanceOf(IllegalStateException.class);
                assertThat(TenantRequestContext.current()).contains(before);
            }
            verify(provider, never()).lookupHostedCheckout(anyString());
            verify(provider, never()).fetchPayments(anyString());
        }
    }

    @Test void actualAmbientTransactionIsRejectedBeforeProviderIoAndNotClearedByService() {
        var provider = provider(paidCheckout(List.of(ORDER)));
        TransactionSynchronizationManager.setActualTransactionActive(true);
        assertThatThrownBy(() -> service.read(provider, attempt(), NORMAL_BUDGET)).isInstanceOf(IllegalStateException.class);
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
        verify(provider, never()).lookupHostedCheckout(anyString());
        verify(provider, never()).fetchPayments(anyString());
    }

    @Test void synchronizationOnlyAndResourceBoundContextsAreRejectedWithoutClearingCallerState() {
        var synchronizedProvider = provider(paidCheckout(List.of(ORDER)));
        TransactionSynchronizationManager.initSynchronization();
        try {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThatThrownBy(() -> service.read(synchronizedProvider, attempt(), NORMAL_BUDGET))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isTrue();
            verify(synchronizedProvider, never()).lookupHostedCheckout(anyString());
            verify(synchronizedProvider, never()).fetchPayments(anyString());
        } finally { TransactionSynchronizationManager.clearSynchronization(); }

        var resourceProvider = provider(paidCheckout(List.of(ORDER)));
        Object resourceKey = new Object(), resourceValue = new Object();
        TransactionSynchronizationManager.bindResource(resourceKey, resourceValue);
        try {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThatThrownBy(() -> service.read(resourceProvider, attempt(), NORMAL_BUDGET))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(TransactionSynchronizationManager.getResource(resourceKey)).isSameAs(resourceValue);
            verify(resourceProvider, never()).lookupHostedCheckout(anyString());
            verify(resourceProvider, never()).fetchPayments(anyString());
        } finally { TransactionSynchronizationManager.unbindResource(resourceKey); }
        assertThat(TransactionSynchronizationManager.getResourceMap()).isEmpty();
    }

    @Test void lookupAndPaymentReadsShareOneMonotonicBudgetRatherThanResettingItPerCall() throws Exception {
        var provider = provider(paidCheckout(List.of(ORDER, OTHER_ORDER)));
        var lookupEntered = new CountDownLatch(1);
        var lookupRelease = new CountDownLatch(1);
        var paymentEntered = new CountDownLatch(1);
        var paymentRelease = new CountDownLatch(1);
        var paymentInterrupted = new CountDownLatch(1);
        var result = new AtomicReference<GatewayProviderDiscovery.ReadResult>();
        var failure = new AtomicReference<Throwable>();
        var elapsedNanos = new AtomicLong();
        when(provider.lookupHostedCheckout(ATTEMPT.toString())).thenAnswer(call -> {
            assertNoAmbientAuthority();
            lookupEntered.countDown();
            if (!lookupRelease.await(3, TimeUnit.SECONDS)) throw new IllegalStateException("Synthetic lookup was not released");
            return new HostedCheckoutLookup(paidCheckout(List.of(ORDER, OTHER_ORDER)),
                    Optional.of(new HostedCheckout(ATTEMPT.toString(), HOSTED)));
        });
        when(provider.fetchPayments(ORDER)).thenAnswer(call -> {
            paymentEntered.countDown();
            return blocking(paymentRelease, paymentInterrupted, List.of(success()));
        });
        Thread caller = Thread.ofVirtual().name("synthetic-discovery-total-budget-test").unstarted(() -> {
            long started = System.nanoTime();
            try { result.set(service.read(provider, attempt(), Duration.ofMillis(800))); }
            catch (RuntimeException rejected) { failure.set(rejected); }
            finally { elapsedNanos.set(System.nanoTime() - started); }
        });
        try {
            caller.start();
            assertThat(lookupEntered.await(2, TimeUnit.SECONDS)).isTrue();
            // Hold a real lookup for part of the shared deadline, without relying
            // on application Clock advancement or a guessed task-start delay.
            assertThat(new CountDownLatch(1).await(300, TimeUnit.MILLISECONDS)).isFalse();
            lookupRelease.countDown();
            assertThat(paymentEntered.await(2, TimeUnit.SECONDS)).isTrue();
            caller.join(Duration.ofSeconds(3));
            assertThat(caller.isAlive()).isFalse();
            assertThat(failure.get()).isNull();
            assertThat(result.get()).isNotNull();
            assertThat(Duration.ofNanos(elapsedNanos.get())).isLessThan(Duration.ofMillis(1100));
            assertThat(paymentInterrupted.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(result.get().evidence().orderReads()).extracting(GatewayDiscoveryEvidence.OrderRead::orderReference)
                    .containsExactly(ORDER, OTHER_ORDER);
            assertThat(result.get().evidence().orderReads()).allSatisfy(read -> assertThat(read.payments()).isEmpty());
            assertThat(classify(result.get()).discoveryComplete()).isFalse();
            assertThat(classify(result.get()).exactCapture()).isEmpty();
            verify(provider, never()).fetchPayments(OTHER_ORDER);
            assertReadOnly(provider);
        } finally {
            lookupRelease.countDown();
            paymentRelease.countDown();
            caller.interrupt();
            caller.join(Duration.ofSeconds(3));
        }
    }

    @Test void providerTaskInterruptionStopsLaterOrdersWithoutInventingCallerInterruption() {
        var provider = provider(paidCheckout(List.of(ORDER, OTHER_ORDER)));
        when(provider.fetchPayments(ORDER)).thenAnswer(call -> {
            assertNoAmbientAuthority();
            Thread.currentThread().interrupt();
            throw new IllegalStateException("synthetic-private-interrupted-provider-body");
        });
        var read = service.read(provider, attempt(), NORMAL_BUDGET);
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
        assertThat(read.evidence().orderReads()).extracting(GatewayDiscoveryEvidence.OrderRead::orderReference)
                .containsExactly(ORDER, OTHER_ORDER);
        assertThat(read.evidence().orderReads()).allSatisfy(order -> assertThat(order.payments()).isEmpty());
        assertThat(classify(read).discoveryComplete()).isFalse();
        assertThat(classify(read).exactCapture()).isEmpty();
        assertThat(read.toString()).doesNotContain("synthetic-private-interrupted-provider-body");
        verify(provider, never()).fetchPayments(OTHER_ORDER);
        assertReadOnly(provider);
    }

    @Test void wrongOrMissingProviderCodeCannotBeAttachedToTheFrozenBinding() {
        for (String code : Arrays.asList("RAZORPAY", "cashfree", null)) {
            var provider = provider(paidCheckout(List.of(ORDER)));
            when(provider.providerCode()).thenReturn(code);
            assertThatThrownBy(() -> service.read(provider, attempt(), NORMAL_BUDGET)).isInstanceOf(IllegalStateException.class);
            verify(provider, never()).lookupHostedCheckout(anyString());
            verify(provider, never()).fetchPayments(anyString());
        }
    }

    @Test void invalidBudgetAndMissingInputsFailBeforeProviderIo() {
        var provider = provider(paidCheckout(List.of(ORDER)));
        for (Duration budget : Arrays.asList(null, Duration.ZERO, Duration.ofNanos(-1), Duration.ofSeconds(60).plusNanos(1))) {
            assertThatThrownBy(() -> service.read(provider, attempt(), budget)).isInstanceOf(RuntimeException.class);
        }
        assertThatThrownBy(() -> service.read(null, attempt(), NORMAL_BUDGET)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> service.read(provider, null, NORMAL_BUDGET)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> new GatewayProviderDiscovery(null)).isInstanceOf(RuntimeException.class);
        verify(provider, never()).lookupHostedCheckout(anyString());
        verify(provider, never()).fetchPayments(anyString());
    }

    @Test void diagnosticsDoNotPrintPaymentOrHostedRedirectDetails() {
        var provider = provider(paidCheckout(List.of(ORDER)));
        when(provider.fetchPayments(ORDER)).thenReturn(List.of(success()));
        var read = service.read(provider, attempt(), NORMAL_BUDGET);
        assertThat(read.toString()).doesNotContain(ATTEMPT.toString(), ACCOUNT.toString(), ORGANISATION.toString(),
                SCHOOL.toString(), PROVIDER_CONFIG.toString(), ORDER, PAYMENT, HOSTED.toString(), AMOUNT.toPlainString());
        assertThat(read.evidence().toString()).doesNotContain(ORDER, PAYMENT, HOSTED.toString());
        assertReadOnly(provider);
    }

    private static PaymentProviderAdapter provider(CheckoutObservation checkout) {
        var provider = mock(PaymentProviderAdapter.class);
        when(provider.providerCode()).thenReturn("CASHFREE");
        when(provider.lookupHostedCheckout(ATTEMPT.toString())).thenAnswer(call -> {
            assertNoAmbientAuthority();
            return new HostedCheckoutLookup(checkout, Optional.of(new HostedCheckout(checkout.checkoutReference(), HOSTED)));
        });
        return provider;
    }

    private static PaymentProviderAdapter providerWithoutHosted(CheckoutObservation checkout) {
        var provider = provider(checkout);
        when(provider.lookupHostedCheckout(ATTEMPT.toString())).thenAnswer(call -> {
            assertNoAmbientAuthority();
            return new HostedCheckoutLookup(checkout, Optional.empty());
        });
        return provider;
    }

    private static <T> T blocking(CountDownLatch release, CountDownLatch interrupted, T value) {
        assertNoAmbientAuthority();
        try {
            if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Synthetic blocked provider was not released");
            return value;
        } catch (InterruptedException cancelled) {
            interrupted.countDown();
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Synthetic provider call interrupted");
        }
    }

    private static void assertNoAmbientAuthority() {
        assertThat(TenantRequestContext.current()).isEmpty();
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    }

    private static void assertReadOnly(PaymentProviderAdapter provider) {
        verify(provider, never()).createHostedCheckout(any(PaymentProviderAdapter.CheckoutRequest.class));
        verify(provider, never()).createHostedCheckout(any(UUID.class), any(BigDecimal.class), anyString(), any(URI.class));
        verify(provider, never()).fetchCheckout(anyString());
        verify(provider, never()).fetchPayment(anyString(), anyString());
        verify(provider, never()).cancelCheckout(any());
        verify(provider, never()).requestRefund(any());
        verify(provider, never()).fetchRefund(anyString(), anyString());
        verify(provider, never()).verifyWebhook(any(), anyString(), anyString());
    }

    private static GatewayDiscoveryEvidence.Result classify(GatewayProviderDiscovery.ReadResult read) {
        return GatewayDiscoveryEvidence.classify(new GatewayDiscoveryEvidence.ExpectedAttempt(ATTEMPT, ACCOUNT,
                binding(), AMOUNT, "INR", CREATED, EXPIRES, "Asia/Kolkata"), read.evidence());
    }

    private static GatewayWorkQueue.ProviderBinding binding() {
        return new GatewayWorkQueue.ProviderBinding(ORGANISATION, SCHOOL, PROVIDER_CONFIG,
                "CASHFREE", "TEST", "synthetic_runner_merchant", "synthetic:runner-credentials");
    }

    private static GatewayAttemptDispatch.ReadRecovery attempt() {
        return new GatewayAttemptDispatch.ReadRecovery(ATTEMPT, ACCOUNT, ATTEMPT, binding(), AMOUNT, "INR",
                "CHECKOUT_PENDING", "ACTIVE", "Asia/Kolkata", LocalDate.of(2026, 10, 13), CREATED, EXPIRES,
                URI.create("https://synthetic-school.example.test/family/payments/" + ATTEMPT),
                1, NOW.plusSeconds(90), true);
    }

    private static CheckoutObservation paidCheckout(List<String> orders) {
        return checkout(ATTEMPT.toString(), ATTEMPT.toString(), AMOUNT, "INR", AMOUNT, CheckoutStatus.PAID, EXPIRES, orders);
    }

    private static CheckoutObservation checkout(String checkout, String attempt, BigDecimal amount, String currency,
            BigDecimal amountPaid, CheckoutStatus status, Instant expiry, List<String> orders) {
        return new CheckoutObservation(checkout, attempt, amount, currency, amountPaid, status, expiry, orders);
    }

    private static PaymentObservation success() { return success(ORDER, PAYMENT); }

    private static PaymentObservation success(String order, String payment) {
        return new PaymentObservation(order, payment, AMOUNT, "INR", PaymentStatus.SUCCESS, true, PAID, "upi");
    }

    private static PaymentObservation failed(String order, String payment) {
        return new PaymentObservation(order, payment, AMOUNT, "INR", PaymentStatus.FAILED, false, null, "card");
    }
}
