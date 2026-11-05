package com.educore.api.finance;

import static com.educore.api.finance.GatewayDiscoveryEvidence.Disposition.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.educore.api.finance.GatewayDiscoveryEvidence.Discovery;
import com.educore.api.finance.GatewayDiscoveryEvidence.ExpectedAttempt;
import com.educore.api.finance.GatewayDiscoveryEvidence.OrderRead;
import com.educore.api.finance.GatewayDiscoveryEvidence.Result;
import com.educore.api.finance.PaymentProviderAdapter.CheckoutObservation;
import com.educore.api.finance.PaymentProviderAdapter.CheckoutStatus;
import com.educore.api.finance.PaymentProviderAdapter.PaymentObservation;
import com.educore.api.finance.PaymentProviderAdapter.PaymentStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** Synthetic pure classification only: no merchant calls, posting, release or database acceptance. */
class GatewayDiscoveryEvidenceTest {
    private static final UUID ATTEMPT = UUID.fromString("00000000-0000-4000-8000-000000000401");
    private static final UUID ACCOUNT = UUID.fromString("00000000-0000-4000-8000-000000000402");
    private static final UUID ORGANISATION = UUID.fromString("00000000-0000-4000-8000-000000000403");
    private static final UUID SCHOOL = UUID.fromString("00000000-0000-4000-8000-000000000404");
    private static final UUID PROVIDER_CONFIG = UUID.fromString("00000000-0000-4000-8000-000000000405");
    private static final BigDecimal AMOUNT = new BigDecimal("1000.00");
    private static final String ORDER = "synthetic_discovery_order_001";
    private static final String OTHER_ORDER = "synthetic_discovery_order_002";
    private static final String PAYMENT = "synthetic_discovery_payment_001";
    private static final Instant CREATED = Instant.parse("2026-10-10T18:25:00Z");
    private static final Instant EXPIRES = CREATED.plusSeconds(3600);
    private static final Instant PAID = Instant.parse("2026-10-10T18:35:00Z");
    private static final Instant OBSERVED = PAID.plusSeconds(300);
    private static final String SCHOOL_ZONE = "Asia/Kolkata";

    @Test
    void exactSingleCapturedSuccessRequiresCompleteIndependentOrderDiscovery() {
        var expected = expected();
        var payment = successfulPayment();
        var result = GatewayDiscoveryEvidence.classify(expected,
                discovery(paidCheckout(), List.of(OrderRead.complete(ORDER, List.of(payment)))));

        assertThat(result.expected()).isSameAs(expected);
        assertThat(result.observedAt()).isEqualTo(OBSERVED);
        assertThat(result.checkout()).contains(paidCheckout());
        assertThat(result.discoveryComplete()).isTrue();
        assertThat(result.pendingPaymentCount()).isZero();
        assertThat(result.successfulPaymentCount()).isEqualTo(1);
        assertThat(result.paymentFacts()).containsExactly(payment);
        assertThat(result.disposition()).isEqualTo(EXACT_CAPTURE);
        assertThat(result.exactCapture()).hasValueSatisfying(candidate -> {
            assertThat(candidate.payment()).isEqualTo(payment);
            assertThat(candidate.originalBusinessDate()).isEqualTo(LocalDate.of(2026, 10, 11));
        });
    }

    @Test
    void numericMoneyEqualityDoesNotRoundOrDependOnStringScale() {
        for (String value : List.of("1000", "1000.0", "1000.00")) {
            var amount = new BigDecimal(value);
            var checkout = checkout(ATTEMPT.toString(), ATTEMPT.toString(), amount, "INR",
                    amount, CheckoutStatus.PAID, EXPIRES, List.of(ORDER));
            var payment = payment(ORDER, PAYMENT, amount, "INR", PaymentStatus.SUCCESS, true, PAID, "upi");

            assertThat(classify(checkout, OrderRead.complete(ORDER, List.of(payment))).disposition())
                    .as("Exact numerical money %s", value).isEqualTo(EXACT_CAPTURE);
        }
    }

    @Test
    void completeFailedRowsInOtherListedOrdersDoNotInventAdditionalSuccess() {
        var checkout = checkout(CheckoutStatus.PAID, AMOUNT, List.of(ORDER, OTHER_ORDER));
        var failed = payment(OTHER_ORDER, "synthetic_failed_payment", AMOUNT, "INR",
                PaymentStatus.FAILED, false, null, "card");
        var result = classify(checkout, OrderRead.complete(ORDER, List.of(successfulPayment())),
                OrderRead.complete(OTHER_ORDER, List.of(failed)));

        assertThat(result.discoveryComplete()).isTrue();
        assertThat(result.paymentFacts()).containsExactly(successfulPayment(), failed);
        assertThat(result.successfulPaymentCount()).isEqualTo(1);
        assertThat(result.pendingPaymentCount()).isZero();
        assertThat(result.disposition()).isEqualTo(EXACT_CAPTURE);
    }

    @Test
    void lateConfirmedPaymentKeepsItsRealBusinessDayRatherThanExpiringMoneyEvidence() {
        var latePaid = EXPIRES.plusSeconds(7200);
        var lateObserved = latePaid.plusSeconds(60);
        var payment = payment(ORDER, PAYMENT, AMOUNT, "INR", PaymentStatus.SUCCESS, true, latePaid, "upi");
        var result = GatewayDiscoveryEvidence.classify(expected(),
                new Discovery(binding(), paidCheckout(), List.of(OrderRead.complete(ORDER, List.of(payment))), lateObserved));

        assertThat(result.disposition()).isEqualTo(EXACT_CAPTURE);
        assertThat(result.exactCapture()).hasValueSatisfying(candidate -> {
            assertThat(candidate.payment().providerPaidAt()).isEqualTo(latePaid);
            assertThat(candidate.originalBusinessDate()).isEqualTo(LocalDate.of(2026, 10, 11));
        });
    }

    @Test
    void frozenSchoolTimeZoneControlsOriginalDayNotServerObservationDay() {
        var zonedExpected = new ExpectedAttempt(ATTEMPT, ACCOUNT, binding(), AMOUNT, "INR",
                CREATED, EXPIRES, "UTC");
        var result = GatewayDiscoveryEvidence.classify(zonedExpected,
                discovery(paidCheckout(), List.of(OrderRead.complete(ORDER, List.of(successfulPayment())))));

        assertThat(result.exactCapture()).hasValueSatisfying(candidate ->
                assertThat(candidate.originalBusinessDate()).isEqualTo(LocalDate.of(2026, 10, 10)));
    }

    @Test
    void missingCheckoutDoesNotTreatAnEmptyResponseAsZeroMoneyProof() {
        var result = classify(null, OrderRead.complete(ORDER, List.of(successfulPayment())));

        assertEmpty(result, INCOMPLETE);
    }

    @Test
    void missingOrUnknownOrderGetKeepsCapturedFactsButCannotFreezeACapture() {
        var checkout = checkout(CheckoutStatus.PAID, AMOUNT, List.of(ORDER, OTHER_ORDER));
        for (List<OrderRead> reads : List.of(
                List.of(OrderRead.complete(ORDER, List.of(successfulPayment()))),
                List.of(OrderRead.complete(ORDER, List.of(successfulPayment())), OrderRead.unknown(OTHER_ORDER)))) {
            var result = GatewayDiscoveryEvidence.classify(expected(), discovery(checkout, reads));

            assertThat(result.discoveryComplete()).isFalse();
            assertThat(result.disposition()).isEqualTo(INCOMPLETE);
            assertThat(result.paymentFacts()).containsExactly(successfulPayment());
            assertThat(result.successfulPaymentCount()).isEqualTo(1);
            assertThat(result.exactCapture()).isEmpty();
        }
    }

    @Test
    void unknownGetIsDifferentFromSuccessfullyObservedEmptyPaymentList() {
        var terminal = checkout(CheckoutStatus.EXPIRED, BigDecimal.ZERO, List.of(ORDER));
        var unknown = classify(terminal, OrderRead.unknown(ORDER));
        var complete = classify(terminal, OrderRead.complete(ORDER, List.of()));

        assertThat(unknown.disposition()).isEqualTo(INCOMPLETE);
        assertThat(unknown.discoveryComplete()).isFalse();
        assertThat(complete.disposition()).isEqualTo(TERMINAL_ZERO_OBSERVED);
        assertThat(complete.discoveryComplete()).isTrue();
        assertThat(complete.exactCapture()).isEmpty();
    }

    @Test
    void terminalZeroRequiresCompleteExactListsAndZeroAggregate() {
        for (CheckoutStatus status : List.of(CheckoutStatus.CANCELLED, CheckoutStatus.EXPIRED)) {
            var result = classify(checkout(status, BigDecimal.ZERO, List.of()), new OrderRead[0]);

            assertThat(result.discoveryComplete()).isTrue();
            assertThat(result.disposition()).isEqualTo(TERMINAL_ZERO_OBSERVED);
            assertThat(result.paymentFacts()).isEmpty();
            assertThat(result.successfulPaymentCount()).isZero();
            assertThat(result.pendingPaymentCount()).isZero();
            assertThat(result.exactCapture()).isEmpty();

            var nonzero = classify(checkout(status, AMOUNT, List.of(ORDER)), OrderRead.complete(ORDER, List.of()));
            assertThat(nonzero.disposition()).isEqualTo(REVIEW_REQUIRED);
            assertThat(nonzero.exactCapture()).isEmpty();
        }
    }

    @Test
    void openEmptyCheckoutIsPendingAndPaidOrPartiallyPaidEmptyCheckoutNeedsReview() {
        for (CheckoutStatus status : List.of(CheckoutStatus.CREATED, CheckoutStatus.PENDING)) {
            var result = classify(checkout(status, BigDecimal.ZERO, List.of()), new OrderRead[0]);
            assertThat(result.discoveryComplete()).isTrue();
            assertThat(result.disposition()).isEqualTo(PENDING);
            assertThat(result.exactCapture()).isEmpty();
        }
        for (CheckoutStatus status : List.of(CheckoutStatus.PAID, CheckoutStatus.PARTIALLY_PAID)) {
            var result = classify(checkout(status, BigDecimal.ZERO, List.of()), new OrderRead[0]);
            assertThat(result.discoveryComplete()).isTrue();
            assertThat(result.disposition()).isEqualTo(REVIEW_REQUIRED);
            assertThat(result.exactCapture()).isEmpty();
        }
    }

    @Test
    void failedDroppedCancelledAndVoidAreNotCapturedSuccessOrPendingMoney() {
        for (PaymentStatus status : List.of(PaymentStatus.FAILED, PaymentStatus.USER_DROPPED,
                PaymentStatus.CANCELLED, PaymentStatus.VOID)) {
            var payment = payment(ORDER, PAYMENT, AMOUNT, "INR", status, false, null, "upi");
            var result = classify(checkout(CheckoutStatus.EXPIRED, BigDecimal.ZERO, List.of(ORDER)),
                    OrderRead.complete(ORDER, List.of(payment)));

            assertThat(result.discoveryComplete()).isTrue();
            assertThat(result.disposition()).as("Uncaptured %s", status).isEqualTo(TERMINAL_ZERO_OBSERVED);
            assertThat(result.paymentFacts()).containsExactly(payment);
            assertThat(result.successfulPaymentCount()).isZero();
            assertThat(result.pendingPaymentCount()).isZero();
        }
    }

    @Test
    void createdPendingAndUncapturedSuccessPreventCaptureAndTerminalZero() {
        for (PaymentStatus status : List.of(PaymentStatus.CREATED, PaymentStatus.PENDING, PaymentStatus.SUCCESS)) {
            var payment = payment(ORDER, PAYMENT, AMOUNT, "INR", status, false, null, "upi");
            var result = classify(checkout(CheckoutStatus.EXPIRED, BigDecimal.ZERO, List.of(ORDER)),
                    OrderRead.complete(ORDER, List.of(payment)));

            assertThat(result.discoveryComplete()).isTrue();
            assertThat(result.disposition()).as("Unresolved %s", status).isEqualTo(PENDING);
            assertThat(result.pendingPaymentCount()).isEqualTo(1);
            assertThat(result.successfulPaymentCount()).isEqualTo(status == PaymentStatus.SUCCESS ? 1 : 0);
            assertThat(result.exactCapture()).isEmpty();
        }
    }

    @Test
    void anyCapturedNonSuccessIsContradictoryNotHarmlessZero() {
        for (PaymentStatus status : PaymentStatus.values()) {
            if (status == PaymentStatus.SUCCESS) continue;
            var payment = payment(ORDER, PAYMENT, AMOUNT, "INR", status, true, PAID, "upi");
            var result = classify(checkout(CheckoutStatus.EXPIRED, BigDecimal.ZERO, List.of(ORDER)),
                    OrderRead.complete(ORDER, List.of(payment)));

            assertThat(result.disposition()).as("Captured contradiction %s", status).isEqualTo(REVIEW_REQUIRED);
            assertThat(result.paymentFacts()).containsExactly(payment);
            assertThat(result.pendingPaymentCount()).isEqualTo(1);
            assertThat(result.exactCapture()).isEmpty();
        }
    }

    @Test
    void anAdditionalPendingPaymentPreventsOtherwiseExactCapture() {
        var pending = payment(OTHER_ORDER, "synthetic_pending_payment", AMOUNT, "INR",
                PaymentStatus.PENDING, false, null, "card");
        var result = classify(checkout(CheckoutStatus.PAID, AMOUNT, List.of(ORDER, OTHER_ORDER)),
                OrderRead.complete(ORDER, List.of(successfulPayment())),
                OrderRead.complete(OTHER_ORDER, List.of(pending)));

        assertThat(result.discoveryComplete()).isTrue();
        assertThat(result.disposition()).isEqualTo(PENDING);
        assertThat(result.successfulPaymentCount()).isEqualTo(1);
        assertThat(result.pendingPaymentCount()).isEqualTo(1);
        assertThat(result.exactCapture()).isEmpty();
    }

    @Test
    void twoDistinctCapturedSuccessesRequireReviewAndRetainBothFacts() {
        var second = payment(OTHER_ORDER, "synthetic_second_success", AMOUNT, "INR",
                PaymentStatus.SUCCESS, true, PAID.plusSeconds(1), "card");
        var result = classify(checkout(CheckoutStatus.PAID, AMOUNT.multiply(BigDecimal.valueOf(2)), List.of(ORDER, OTHER_ORDER)),
                OrderRead.complete(ORDER, List.of(successfulPayment())),
                OrderRead.complete(OTHER_ORDER, List.of(second)));

        assertThat(result.discoveryComplete()).isTrue();
        assertThat(result.successfulPaymentCount()).isEqualTo(2);
        assertThat(result.paymentFacts()).containsExactly(successfulPayment(), second);
        assertThat(result.disposition()).isEqualTo(REVIEW_REQUIRED);
        assertThat(result.exactCapture()).isEmpty();
    }

    @Test
    void paymentReferenceMustBeGloballyUniqueAcrossAndWithinOrderReads() {
        var sameReferenceOtherOrder = payment(OTHER_ORDER, PAYMENT, AMOUNT, "INR",
                PaymentStatus.SUCCESS, true, PAID, "upi");
        var crossOrder = classify(checkout(CheckoutStatus.PAID, AMOUNT, List.of(ORDER, OTHER_ORDER)),
                OrderRead.complete(ORDER, List.of(successfulPayment())),
                OrderRead.complete(OTHER_ORDER, List.of(sameReferenceOtherOrder)));
        var sameOrder = classify(paidCheckout(), OrderRead.complete(ORDER,
                List.of(successfulPayment(), successfulPayment())));

        for (Result result : List.of(crossOrder, sameOrder)) {
            assertThat(result.discoveryComplete()).isFalse();
            assertThat(result.disposition()).isEqualTo(REVIEW_REQUIRED);
            assertThat(result.successfulPaymentCount()).isEqualTo(1);
            assertThat(result.exactCapture()).isEmpty();
        }
        assertThat(crossOrder.paymentFacts()).containsExactly(successfulPayment(), sameReferenceOtherOrder);
        assertThat(sameOrder.paymentFacts()).containsExactly(successfulPayment());
    }

    @Test
    void repeatedCompleteOrUnknownReadsNeverWinByArrivalOrder() {
        for (List<OrderRead> reads : List.of(
                List.of(OrderRead.complete(ORDER, List.of(successfulPayment())), OrderRead.complete(ORDER, List.of())),
                List.of(OrderRead.complete(ORDER, List.of()), OrderRead.complete(ORDER, List.of(successfulPayment()))),
                List.of(OrderRead.unknown(ORDER), OrderRead.complete(ORDER, List.of(successfulPayment()))))) {
            var result = GatewayDiscoveryEvidence.classify(expected(), discovery(paidCheckout(), reads));

            assertThat(result.discoveryComplete()).isFalse();
            assertThat(result.disposition()).isEqualTo(REVIEW_REQUIRED);
            assertThat(result.paymentFacts()).containsExactly(successfulPayment());
            assertThat(result.exactCapture()).isEmpty();
        }
    }

    @Test
    void conflictingFactsForOnePaymentIdentityHaveArrivalOrderIndependentCounts() {
        for (PaymentObservation conflicting : List.of(
                payment(ORDER, PAYMENT, AMOUNT, "INR", PaymentStatus.PENDING, false, null, "upi"),
                payment(ORDER, PAYMENT, AMOUNT, "INR", PaymentStatus.SUCCESS, false, PAID, "upi"),
                payment(ORDER, PAYMENT, AMOUNT, "INR", PaymentStatus.FAILED, false, null, "upi"))) {
            for (List<PaymentObservation> rows : List.of(
                    List.of(conflicting, successfulPayment()), List.of(successfulPayment(), conflicting))) {
                var result = classify(paidCheckout(), OrderRead.complete(ORDER, rows));

                assertThat(result.paymentFacts()).containsExactlyElementsOf(rows);
                assertThat(result.successfulPaymentCount()).isEqualTo(1);
                assertThat(result.pendingPaymentCount()).isEqualTo(conflicting.status() == PaymentStatus.FAILED ? 0 : 1);
                assertThat(result.discoveryComplete()).isFalse();
                assertThat(result.disposition()).isEqualTo(REVIEW_REQUIRED);
                assertThat(result.exactCapture()).isEmpty();
            }
        }
    }

    @Test
    void twoSuccessfulIdentitiesIncludingOneUncapturedCannotHideBehindPending() {
        var uncaptured = payment(OTHER_ORDER, "synthetic_uncaptured_success", AMOUNT, "INR",
                PaymentStatus.SUCCESS, false, PAID, "card");
        var result = classify(checkout(CheckoutStatus.PAID, AMOUNT, List.of(ORDER, OTHER_ORDER)),
                completeSuccess(), OrderRead.complete(OTHER_ORDER, List.of(uncaptured)));

        assertThat(result.discoveryComplete()).isTrue();
        assertThat(result.successfulPaymentCount()).isEqualTo(2);
        assertThat(result.pendingPaymentCount()).isEqualTo(1);
        assertThat(result.paymentFacts()).containsExactly(successfulPayment(), uncaptured);
        assertThat(result.disposition()).isEqualTo(REVIEW_REQUIRED);
        assertThat(result.exactCapture()).isEmpty();
    }

    @Test
    void extraUnlistedOrderGetsAndWrongOrderRowsCannotAttachForeignFacts() {
        var foreign = payment(OTHER_ORDER, "synthetic_foreign_payment", AMOUNT, "INR",
                PaymentStatus.SUCCESS, true, PAID, "upi");
        var extra = classify(paidCheckout(), OrderRead.complete(ORDER, List.of(successfulPayment())),
                OrderRead.complete(OTHER_ORDER, List.of(foreign)));
        var wrongRow = classify(paidCheckout(), OrderRead.complete(ORDER, List.of(foreign)));

        assertThat(extra.paymentFacts()).containsExactly(successfulPayment());
        assertThat(wrongRow.paymentFacts()).isEmpty();
        for (Result result : List.of(extra, wrongRow)) {
            assertThat(result.discoveryComplete()).isFalse();
            assertThat(result.disposition()).isEqualTo(REVIEW_REQUIRED);
            assertThat(result.exactCapture()).isEmpty();
        }
    }

    @Test
    void providerBindingMustMatchEveryFrozenTenantMerchantAndCredentialLabel() {
        var binding = binding();
        var mismatches = List.of(
                new GatewayWorkQueue.ProviderBinding(UUID.randomUUID(), SCHOOL, PROVIDER_CONFIG,
                        "CASHFREE", "TEST", binding.merchantReference(), binding.credentialsReference()),
                new GatewayWorkQueue.ProviderBinding(ORGANISATION, UUID.randomUUID(), PROVIDER_CONFIG,
                        "CASHFREE", "TEST", binding.merchantReference(), binding.credentialsReference()),
                new GatewayWorkQueue.ProviderBinding(ORGANISATION, SCHOOL, UUID.randomUUID(),
                        "CASHFREE", "TEST", binding.merchantReference(), binding.credentialsReference()),
                new GatewayWorkQueue.ProviderBinding(ORGANISATION, SCHOOL, PROVIDER_CONFIG,
                        "OTHER", "TEST", binding.merchantReference(), binding.credentialsReference()),
                new GatewayWorkQueue.ProviderBinding(ORGANISATION, SCHOOL, PROVIDER_CONFIG,
                        "CASHFREE", "LIVE", binding.merchantReference(), binding.credentialsReference()),
                new GatewayWorkQueue.ProviderBinding(ORGANISATION, SCHOOL, PROVIDER_CONFIG,
                        "CASHFREE", "TEST", "synthetic_other_merchant", binding.credentialsReference()),
                new GatewayWorkQueue.ProviderBinding(ORGANISATION, SCHOOL, PROVIDER_CONFIG,
                        "CASHFREE", "TEST", binding.merchantReference(), "synthetic:other-not-enabled"));

        for (var mismatch : mismatches) {
            var result = GatewayDiscoveryEvidence.classify(expected(), new Discovery(mismatch, paidCheckout(),
                    List.of(OrderRead.complete(ORDER, List.of(successfulPayment()))), OBSERVED));
            assertEmpty(result, REVIEW_REQUIRED);
        }
    }

    @Test
    void exactCheckoutRequiresBothDurableUuidReferencesCurrencyExpiryAndStatus() {
        for (String reference : Arrays.asList(null, "", UUID.randomUUID().toString(), ATTEMPT + " ")) {
            assertEmpty(classify(checkout(reference, ATTEMPT.toString(), AMOUNT, "INR", AMOUNT,
                    CheckoutStatus.PAID, EXPIRES, List.of(ORDER)), completeSuccess()), REVIEW_REQUIRED);
            assertEmpty(classify(checkout(ATTEMPT.toString(), reference, AMOUNT, "INR", AMOUNT,
                    CheckoutStatus.PAID, EXPIRES, List.of(ORDER)), completeSuccess()), REVIEW_REQUIRED);
        }
        for (String currency : Arrays.asList(null, "", "inr", "USD", "INR ")) {
            assertEmpty(classify(checkout(ATTEMPT.toString(), ATTEMPT.toString(), AMOUNT, currency, AMOUNT,
                    CheckoutStatus.PAID, EXPIRES, List.of(ORDER)), completeSuccess()), REVIEW_REQUIRED);
        }
        for (Instant expiry : Arrays.asList(null, EXPIRES.minusNanos(1), EXPIRES.plusNanos(1))) {
            assertEmpty(classify(checkout(ATTEMPT.toString(), ATTEMPT.toString(), AMOUNT, "INR", AMOUNT,
                    CheckoutStatus.PAID, expiry, List.of(ORDER)), completeSuccess()), REVIEW_REQUIRED);
        }
        assertEmpty(classify(checkout(ATTEMPT.toString(), ATTEMPT.toString(), AMOUNT, "INR", AMOUNT,
                null, EXPIRES, List.of(ORDER)), completeSuccess()), REVIEW_REQUIRED);
    }

    @Test
    void invalidCheckoutMoneyCannotHideBehindPendingState() {
        for (BigDecimal amount : Arrays.asList(null, BigDecimal.ZERO, new BigDecimal("-1.00"),
                new BigDecimal("999.99"), new BigDecimal("1000.01"), new BigDecimal("1000.000"))) {
            assertEmpty(classify(checkout(ATTEMPT.toString(), ATTEMPT.toString(), amount, "INR", BigDecimal.ZERO,
                    CheckoutStatus.PENDING, EXPIRES, List.of(ORDER)), OrderRead.complete(ORDER, List.of())), REVIEW_REQUIRED);
        }
        for (BigDecimal paid : Arrays.asList(null, new BigDecimal("-0.01"),
                new BigDecimal("0.000"), new BigDecimal("100000000.01"))) {
            assertEmpty(classify(checkout(ATTEMPT.toString(), ATTEMPT.toString(), AMOUNT, "INR", paid,
                    CheckoutStatus.PENDING, EXPIRES, List.of(ORDER)), OrderRead.complete(ORDER, List.of())), REVIEW_REQUIRED);
        }
    }

    @Test
    void exactSuccessCannotAcceptMismatchedAggregateOrNonPaidCheckout() {
        for (BigDecimal paid : List.of(BigDecimal.ZERO, new BigDecimal("999.99"), new BigDecimal("1000.01"))) {
            var result = classify(checkout(CheckoutStatus.PAID, paid, List.of(ORDER)), completeSuccess());
            assertThat(result.discoveryComplete()).isTrue();
            assertThat(result.disposition()).isEqualTo(REVIEW_REQUIRED);
            assertThat(result.paymentFacts()).containsExactly(successfulPayment());
            assertThat(result.exactCapture()).isEmpty();
        }
        for (CheckoutStatus status : CheckoutStatus.values()) {
            if (status == CheckoutStatus.PAID) continue;
            var result = classify(checkout(status, AMOUNT, List.of(ORDER)), completeSuccess());
            assertThat(result.disposition()).as("Captured success with %s link", status).isEqualTo(REVIEW_REQUIRED);
            assertThat(result.exactCapture()).isEmpty();
        }
    }

    @Test
    void contradictoryCheckoutStatusAndPaidAggregateRequireReviewEvenWithUnknownReads() {
        var contradictions = List.of(
                checkout(CheckoutStatus.CREATED, new BigDecimal("1.00"), List.of(ORDER)),
                checkout(CheckoutStatus.PENDING, new BigDecimal("1.00"), List.of(ORDER)),
                checkout(CheckoutStatus.PAID, new BigDecimal("999.99"), List.of(ORDER)),
                checkout(CheckoutStatus.PARTIALLY_PAID, BigDecimal.ZERO, List.of(ORDER)),
                checkout(CheckoutStatus.PARTIALLY_PAID, AMOUNT, List.of(ORDER)),
                checkout(CheckoutStatus.EXPIRED, new BigDecimal("1.00"), List.of(ORDER)),
                checkout(CheckoutStatus.CANCELLED, new BigDecimal("1.00"), List.of(ORDER)),
                checkout(CheckoutStatus.PAID, new BigDecimal("1000.01"), List.of(ORDER)));

        for (CheckoutObservation checkout : contradictions) {
            var result = classify(checkout, OrderRead.unknown(ORDER));
            assertThat(result.discoveryComplete()).isFalse();
            assertThat(result.disposition()).isEqualTo(REVIEW_REQUIRED);
            assertThat(result.exactCapture()).isEmpty();
        }
    }

    @Test
    void listedOrdersMustBeUniqueCanonicalAndBoundedBeforeAnyFactIsAttached() {
        for (String reference : List.of("", " leading", "trailing ", "../order", "https://example.test/order",
                "synthetic\norder", "a".repeat(101))) {
            assertEmpty(classify(checkout(CheckoutStatus.PAID, AMOUNT, List.of(reference)), completeSuccess()), REVIEW_REQUIRED);
        }
        assertEmpty(classify(checkout(CheckoutStatus.PAID, AMOUNT, List.of(ORDER, ORDER)), completeSuccess()), REVIEW_REQUIRED);
        var tooManyOrders = IntStream.range(0, 101).mapToObj(index -> "synthetic_order_" + index).toList();
        assertEmpty(classify(checkout(CheckoutStatus.PAID, AMOUNT, tooManyOrders), completeSuccess()), REVIEW_REQUIRED);
    }

    @Test
    void malformedPaymentRowsAreExcludedAndCannotProveCompleteDiscovery() {
        var malformed = new ArrayList<PaymentObservation>();
        for (String reference : Arrays.asList(null, "", " leading", "../payment", "a".repeat(101))) {
            malformed.add(payment(ORDER, reference, AMOUNT, "INR", PaymentStatus.SUCCESS, true, PAID, "upi"));
        }
        for (BigDecimal amount : Arrays.asList(null, BigDecimal.ZERO, new BigDecimal("-1.00"),
                new BigDecimal("1000.000"), new BigDecimal("100000000.01"))) {
            malformed.add(payment(ORDER, PAYMENT, amount, "INR", PaymentStatus.SUCCESS, true, PAID, "upi"));
        }
        for (String currency : Arrays.asList(null, "", "inr", "INR ")) {
            malformed.add(payment(ORDER, PAYMENT, AMOUNT, currency, PaymentStatus.SUCCESS, true, PAID, "upi"));
        }
        malformed.add(payment(ORDER, PAYMENT, AMOUNT, "INR", null, true, PAID, "upi"));
        malformed.add(payment(ORDER, PAYMENT, AMOUNT, "INR", PaymentStatus.SUCCESS, true, null, "upi"));
        malformed.add(payment(ORDER, PAYMENT, AMOUNT, "INR", PaymentStatus.SUCCESS, true, OBSERVED.plusNanos(1), "upi"));
        malformed.add(payment(ORDER, PAYMENT, AMOUNT, "INR", PaymentStatus.SUCCESS, true, PAID, "customer@example.test"));

        for (PaymentObservation row : malformed) {
            var result = classify(paidCheckout(), OrderRead.complete(ORDER, List.of(row)));
            assertThat(result.discoveryComplete()).isFalse();
            assertThat(result.disposition()).isEqualTo(REVIEW_REQUIRED);
            assertThat(result.paymentFacts()).isEmpty();
            assertThat(result.exactCapture()).isEmpty();
        }
    }

    @Test
    void malformedRowCannotEraseAValidCapturedFactOrAllowPartialCaptureProof() {
        var malformed = payment(ORDER, "../unsafe_payment", AMOUNT, "INR", PaymentStatus.FAILED, false, null, "upi");
        var result = classify(paidCheckout(), OrderRead.complete(ORDER, List.of(malformed, successfulPayment())));

        assertThat(result.paymentFacts()).containsExactly(successfulPayment());
        assertThat(result.successfulPaymentCount()).isEqualTo(1);
        assertThat(result.discoveryComplete()).isFalse();
        assertThat(result.disposition()).isEqualTo(REVIEW_REQUIRED);
        assertThat(result.exactCapture()).isEmpty();
    }

    @Test
    void individuallyValidForeignCurrencyAndAmountFactsRemainVisibleForReview() {
        for (PaymentObservation row : List.of(
                payment(ORDER, PAYMENT, new BigDecimal("999.99"), "INR", PaymentStatus.SUCCESS, true, PAID, "upi"),
                payment(ORDER, PAYMENT, AMOUNT, "USD", PaymentStatus.SUCCESS, true, PAID, "card"))) {
            var result = classify(paidCheckout(), OrderRead.complete(ORDER, List.of(row)));

            assertThat(result.discoveryComplete()).isTrue();
            assertThat(result.disposition()).isEqualTo(REVIEW_REQUIRED);
            assertThat(result.paymentFacts()).containsExactly(row);
            assertThat(result.exactCapture()).isEmpty();
        }
    }

    @Test
    void preIntentPaymentIsRetainedForReviewAndPreIntentObservationAttachesNothing() {
        var before = payment(ORDER, PAYMENT, AMOUNT, "INR", PaymentStatus.SUCCESS, true,
                CREATED.minusNanos(1), "upi");
        var paymentResult = classify(paidCheckout(), OrderRead.complete(ORDER, List.of(before)));
        assertThat(paymentResult.paymentFacts()).containsExactly(before);
        assertThat(paymentResult.disposition()).isEqualTo(REVIEW_REQUIRED);
        assertThat(paymentResult.exactCapture()).isEmpty();

        var observationResult = GatewayDiscoveryEvidence.classify(expected(), new Discovery(binding(), paidCheckout(),
                List.of(completeSuccess()), CREATED.minusNanos(1)));
        assertEmpty(observationResult, REVIEW_REQUIRED);

        var boundary = payment(ORDER, PAYMENT, AMOUNT, "INR", PaymentStatus.SUCCESS, true, CREATED, "upi");
        var boundaryResult = GatewayDiscoveryEvidence.classify(expected(), new Discovery(binding(), paidCheckout(),
                List.of(OrderRead.complete(ORDER, List.of(boundary))), CREATED));
        assertThat(boundaryResult.disposition()).isEqualTo(EXACT_CAPTURE);
    }

    @Test
    void invalidExpectedIdentityMoneyDatesCurrencyAndZoneUseGenericErrors() {
        for (BigDecimal amount : Arrays.asList(null, BigDecimal.ZERO, new BigDecimal("-1.00"),
                new BigDecimal("1000.000"), new BigDecimal("100000000.01"))) {
            assertInvalidIntent(() -> new ExpectedAttempt(ATTEMPT, ACCOUNT, binding(), amount, "INR",
                    CREATED, EXPIRES, SCHOOL_ZONE));
        }
        for (String currency : Arrays.asList(null, "USD", "inr", "INR ")) {
            assertInvalidIntent(() -> new ExpectedAttempt(ATTEMPT, ACCOUNT, binding(), AMOUNT, currency,
                    CREATED, EXPIRES, SCHOOL_ZONE));
        }
        for (String zone : Arrays.asList(null, "", "synthetic/not-a-zone")) {
            assertInvalidIntent(() -> new ExpectedAttempt(ATTEMPT, ACCOUNT, binding(), AMOUNT, "INR",
                    CREATED, EXPIRES, zone));
        }
        assertInvalidIntent(() -> new ExpectedAttempt(null, ACCOUNT, binding(), AMOUNT, "INR", CREATED, EXPIRES, SCHOOL_ZONE));
        assertInvalidIntent(() -> new ExpectedAttempt(ATTEMPT, null, binding(), AMOUNT, "INR", CREATED, EXPIRES, SCHOOL_ZONE));
        assertInvalidIntent(() -> new ExpectedAttempt(ATTEMPT, ACCOUNT, null, AMOUNT, "INR", CREATED, EXPIRES, SCHOOL_ZONE));
        assertInvalidIntent(() -> new ExpectedAttempt(ATTEMPT, ACCOUNT, binding(), AMOUNT, "INR", null, EXPIRES, SCHOOL_ZONE));
        for (Instant expires : Arrays.asList(null, CREATED.minusNanos(1), CREATED, CREATED.plusSeconds(86400).plusNanos(1))) {
            assertInvalidIntent(() -> new ExpectedAttempt(ATTEMPT, ACCOUNT, binding(), AMOUNT, "INR", CREATED, expires, SCHOOL_ZONE));
        }
    }

    @Test
    void discoveryContainersRequireBoundedExplicitUnknownOrCompleteOutcomes() {
        var tooManyPayments = IntStream.range(0, 101).mapToObj(index ->
                payment(ORDER, "synthetic_payment_" + index, AMOUNT, "INR", PaymentStatus.FAILED, false, null, "upi")).toList();
        var tooManyReads = IntStream.range(0, 101).mapToObj(index -> OrderRead.unknown("synthetic_order_" + index)).toList();

        assertThatThrownBy(() -> OrderRead.complete(ORDER, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OrderRead.complete(ORDER, tooManyPayments)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OrderRead.unknown("../order")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Discovery(binding(), paidCheckout(), tooManyReads, OBSERVED)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Discovery(null, paidCheckout(), List.of(), OBSERVED)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Discovery(binding(), paidCheckout(), null, OBSERVED)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Discovery(binding(), paidCheckout(), List.of(), null)).isInstanceOf(IllegalArgumentException.class);

        var maximumRows = tooManyPayments.subList(0, 100);
        var maximumOrders = IntStream.range(0, 100).mapToObj(index -> "synthetic_order_" + index).toList();
        var reads = maximumOrders.stream().map(order -> OrderRead.complete(order, List.of())).toList();
        assertThat(OrderRead.complete(ORDER, maximumRows).payments()).hasValueSatisfying(rows -> assertThat(rows).hasSize(100));
        assertThat(GatewayDiscoveryEvidence.classify(expected(), discovery(
                checkout(CheckoutStatus.EXPIRED, BigDecimal.ZERO, maximumOrders), reads)).disposition())
                .isEqualTo(TERMINAL_ZERO_OBSERVED);
    }

    @Test
    void maximumCompleteDiscoveryDerivesBothCountsWithoutTruncatingTenThousandFacts() {
        var orders = IntStream.range(0, 100).mapToObj(index -> "synthetic_max_order_" + index).toList();
        var reads = orders.stream().map(order -> OrderRead.complete(order,
                IntStream.range(0, 100).mapToObj(index -> payment(order, order + "_payment_" + index,
                        AMOUNT, "INR", PaymentStatus.SUCCESS, false, null, "upi")).toList())).toList();
        var result = GatewayDiscoveryEvidence.classify(expected(), discovery(
                checkout(CheckoutStatus.EXPIRED, BigDecimal.ZERO, orders), reads));

        assertThat(result.discoveryComplete()).isTrue();
        assertThat(result.paymentFacts()).hasSize(10000);
        assertThat(result.successfulPaymentCount()).isEqualTo(10000);
        assertThat(result.pendingPaymentCount()).isEqualTo(10000);
        assertThat(result.disposition()).isEqualTo(REVIEW_REQUIRED);
        assertThat(result.exactCapture()).isEmpty();
    }

    @Test
    void twoSmallerCapturedPaymentsCannotBeCollapsedIntoOneExactReceiptCandidate() {
        var first = payment(ORDER, PAYMENT, new BigDecimal("500.00"), "INR", PaymentStatus.SUCCESS, true, PAID, "upi");
        var second = payment(OTHER_ORDER, "synthetic_second_half", new BigDecimal("500.00"), "INR",
                PaymentStatus.SUCCESS, true, PAID.plusSeconds(1), "card");
        var result = classify(checkout(CheckoutStatus.PAID, AMOUNT, List.of(ORDER, OTHER_ORDER)),
                OrderRead.complete(ORDER, List.of(first)), OrderRead.complete(OTHER_ORDER, List.of(second)));

        assertThat(result.discoveryComplete()).isTrue();
        assertThat(result.paymentFacts()).containsExactly(first, second);
        assertThat(result.successfulPaymentCount()).isEqualTo(2);
        assertThat(result.pendingPaymentCount()).isZero();
        assertThat(result.disposition()).isEqualTo(REVIEW_REQUIRED);
        assertThat(result.exactCapture()).isEmpty();
    }

    @Test
    void extremeTimeAndNullContainerEntriesFailGenericallyWithoutRetainingPayloads() {
        assertInvalidIntent(() -> new ExpectedAttempt(ATTEMPT, ACCOUNT, binding(), AMOUNT, "INR",
                Instant.MAX.minusSeconds(1), Instant.MAX, SCHOOL_ZONE));
        assertThatThrownBy(() -> OrderRead.complete(ORDER, Arrays.asList((PaymentObservation) null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Bounded authenticated gateway discovery required").hasNoCause();
        assertThatThrownBy(() -> new Discovery(binding(), paidCheckout(), Arrays.asList((OrderRead) null), OBSERVED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Bounded authenticated gateway discovery required").hasNoCause();
    }

    @Test
    void inputListsAndReturnedFactsAreImmutableSnapshots() {
        var orders = new ArrayList<>(List.of(ORDER));
        var payments = new ArrayList<>(List.of(successfulPayment()));
        var read = OrderRead.complete(ORDER, payments);
        var reads = new ArrayList<>(List.of(read));
        var checkout = checkout(CheckoutStatus.PAID, AMOUNT, orders);
        var discovery = discovery(checkout, reads);
        payments.clear();
        reads.clear();
        orders.clear();
        var result = GatewayDiscoveryEvidence.classify(expected(), discovery);

        assertThat(result.disposition()).isEqualTo(EXACT_CAPTURE);
        assertThat(result.paymentFacts()).containsExactly(successfulPayment());
        assertThat(discovery.orderReads()).containsExactly(read);
        assertThat(checkout.orderReferences()).containsExactly(ORDER);
        assertThatThrownBy(() -> read.payments().orElseThrow().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> discovery.orderReads().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> result.paymentFacts().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> result.checkout().orElseThrow().orderReferences().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void internalDiagnosticsNeverEchoPaymentTenantOrCredentialBindingPayloads() {
        var expected = expected();
        var read = completeSuccess();
        var discovery = discovery(paidCheckout(), List.of(read));
        var result = GatewayDiscoveryEvidence.classify(expected, discovery);

        for (Object value : List.of(expected, read, discovery, result, result.exactCapture().orElseThrow())) {
            assertThat(value.toString()).doesNotContain(ATTEMPT.toString(), ACCOUNT.toString(), ORGANISATION.toString(),
                    SCHOOL.toString(), PROVIDER_CONFIG.toString(), ORDER, PAYMENT,
                    binding().merchantReference(), binding().credentialsReference(), AMOUNT.toPlainString(), PAID.toString());
        }
    }

    private static ExpectedAttempt expected() {
        return new ExpectedAttempt(ATTEMPT, ACCOUNT, binding(), AMOUNT, "INR", CREATED, EXPIRES, SCHOOL_ZONE);
    }

    private static GatewayWorkQueue.ProviderBinding binding() {
        return new GatewayWorkQueue.ProviderBinding(ORGANISATION, SCHOOL, PROVIDER_CONFIG,
                "CASHFREE", "TEST", "synthetic_discovery_merchant", "synthetic:discovery-not-enabled");
    }

    private static CheckoutObservation paidCheckout() {
        return checkout(CheckoutStatus.PAID, AMOUNT, List.of(ORDER));
    }

    private static CheckoutObservation checkout(CheckoutStatus status, BigDecimal amountPaid, List<String> orders) {
        return checkout(ATTEMPT.toString(), ATTEMPT.toString(), AMOUNT, "INR", amountPaid, status, EXPIRES, orders);
    }

    private static CheckoutObservation checkout(String reference, String attemptReference, BigDecimal amount,
            String currency, BigDecimal paid, CheckoutStatus status, Instant expiresAt, List<String> orders) {
        return new CheckoutObservation(reference, attemptReference, amount, currency, paid, status, expiresAt, orders);
    }

    private static PaymentObservation successfulPayment() {
        return payment(ORDER, PAYMENT, AMOUNT, "INR", PaymentStatus.SUCCESS, true, PAID, "upi");
    }

    private static PaymentObservation payment(String order, String reference, BigDecimal amount, String currency,
            PaymentStatus status, boolean captured, Instant paidAt, String method) {
        return new PaymentObservation(order, reference, amount, currency, status, captured, paidAt, method);
    }

    private static OrderRead completeSuccess() {
        return OrderRead.complete(ORDER, List.of(successfulPayment()));
    }

    private static Discovery discovery(CheckoutObservation checkout, List<OrderRead> reads) {
        return new Discovery(binding(), checkout, reads, OBSERVED);
    }

    private static Result classify(CheckoutObservation checkout, OrderRead... reads) {
        return GatewayDiscoveryEvidence.classify(expected(), discovery(checkout, List.of(reads)));
    }

    private static void assertEmpty(Result result, GatewayDiscoveryEvidence.Disposition disposition) {
        assertThat(result.disposition()).isEqualTo(disposition);
        assertThat(result.discoveryComplete()).isFalse();
        assertThat(result.checkout()).isEmpty();
        assertThat(result.paymentFacts()).isEmpty();
        assertThat(result.pendingPaymentCount()).isZero();
        assertThat(result.successfulPaymentCount()).isZero();
        assertThat(result.exactCapture()).isEmpty();
    }

    private static void assertInvalidIntent(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Exact immutable scoped gateway intent required")
                .hasNoCause();
    }
}
