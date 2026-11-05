package com.educore.api.finance;

import static com.educore.api.finance.GatewayPaymentEvidence.CaptureDecision.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.educore.api.finance.GatewayPaymentEvidence.ExpectedPayment;
import com.educore.api.finance.GatewayPaymentEvidence.RefundDecision;
import com.educore.api.finance.PaymentProviderAdapter.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Pure synthetic provider-evidence checks, not gateway or database acceptance. */
class GatewayPaymentEvidenceTest {
    private static final UUID ATTEMPT = UUID.fromString("00000000-0000-4000-8000-000000000101");
    private static final UUID REFUND = UUID.fromString("00000000-0000-4000-8000-000000000102");
    private static final String CHECKOUT = "synthetic_checkout_001";
    private static final String ORDER = "synthetic_order_001";
    private static final String PAYMENT = "synthetic_payment_001";
    private static final BigDecimal AMOUNT = new BigDecimal("1000.00");
    private static final Instant NOW = Instant.parse("2026-10-10T06:30:00Z");

    @Test
    void verifiesOnlyExactDurableAttemptAndSuccessfulServerPaymentEvidence() {
        var expected = expected();
        var checkout = checkout();
        var payment = payment();

        assertThat(GatewayPaymentEvidence.capture(expected, checkout, payment, NOW))
                .isEqualTo(VERIFIED_SUCCESS);
        assertThat(expected.amount()).isEqualTo(AMOUNT);
        assertThat(checkout.amountPaid()).isEqualTo(AMOUNT);
        assertThat(payment.status()).isEqualTo(PaymentStatus.SUCCESS);
    }

    @Test
    void acceptsNumericallyEqualMoneyWithoutRoundingOrStringScaleDependence() {
        for (String amount : List.of("1000", "1000.0", "1000.00")) {
            var value = new BigDecimal(amount);
            assertThat(capture(checkout(CHECKOUT, ATTEMPT.toString(), value, "INR", value,
                            CheckoutStatus.PAID, List.of(ORDER)),
                    payment(ORDER, PAYMENT, value, "INR", PaymentStatus.SUCCESS, NOW)))
                    .as("Exact synthetic observed money %s", amount).isEqualTo(VERIFIED_SUCCESS);
        }
    }

    @Test
    void rejectsOrphanObservationsAndMissingProviderStatuses() {
        assertThat(capture(null, payment())).isEqualTo(REVIEW_REQUIRED);
        assertThat(capture(checkout(), null)).isEqualTo(REVIEW_REQUIRED);
        assertThat(capture(checkout(CHECKOUT, ATTEMPT.toString(), AMOUNT, "INR", AMOUNT,
                null, List.of(ORDER)), payment())).isEqualTo(REVIEW_REQUIRED);
        assertThat(capture(checkout(), payment(ORDER, PAYMENT, AMOUNT, "INR", null, NOW)))
                .isEqualTo(REVIEW_REQUIRED);
    }

    @Test
    void rejectsDifferentOrMissingCheckoutAndDurableAttemptReferences() {
        for (String reference : Arrays.asList(null, "", "synthetic_checkout_other", CHECKOUT + " ")) {
            assertThat(capture(checkout(reference, ATTEMPT.toString(), AMOUNT, "INR", AMOUNT,
                    CheckoutStatus.PAID, List.of(ORDER)), payment())).isEqualTo(REVIEW_REQUIRED);
        }
        for (String reference : Arrays.asList(null, "", UUID.randomUUID().toString(), ATTEMPT + " ")) {
            assertThat(capture(checkout(CHECKOUT, reference, AMOUNT, "INR", AMOUNT,
                    CheckoutStatus.PAID, List.of(ORDER)), payment())).isEqualTo(REVIEW_REQUIRED);
        }
    }

    @Test
    void rejectsCheckoutOrPaymentCurrencyMismatchRatherThanConvertingIt() {
        for (String currency : Arrays.asList(null, "", "inr", "USD", "INR ")) {
            assertThat(capture(checkout(CHECKOUT, ATTEMPT.toString(), AMOUNT, currency, AMOUNT,
                    CheckoutStatus.PAID, List.of(ORDER)), payment())).isEqualTo(REVIEW_REQUIRED);
            assertThat(capture(checkout(), payment(ORDER, PAYMENT, AMOUNT, currency,
                    PaymentStatus.SUCCESS, NOW))).isEqualTo(REVIEW_REQUIRED);
        }
    }

    @Test
    void rejectsMismatchedMissingNegativeAndOverprecisionMoneyAtEveryCaptureBoundary() {
        for (BigDecimal amount : Arrays.asList(null, BigDecimal.ZERO, new BigDecimal("-1000.00"),
                new BigDecimal("999.99"), new BigDecimal("1000.01"), new BigDecimal("1000.000"))) {
            assertThat(capture(checkout(CHECKOUT, ATTEMPT.toString(), amount, "INR", AMOUNT,
                    CheckoutStatus.PAID, List.of(ORDER)), payment())).isEqualTo(REVIEW_REQUIRED);
            assertThat(capture(checkout(), payment(ORDER, PAYMENT, amount, "INR",
                    PaymentStatus.SUCCESS, NOW))).isEqualTo(REVIEW_REQUIRED);
            assertThat(capture(checkout(CHECKOUT, ATTEMPT.toString(), AMOUNT, "INR", amount,
                    CheckoutStatus.PAID, List.of(ORDER)), payment())).isEqualTo(REVIEW_REQUIRED);
        }
    }

    @Test
    void doesNotAcceptAnUnexpectedAggregatePaidAmountFromMultiplePayments() {
        var checkout = checkout(CHECKOUT, ATTEMPT.toString(), AMOUNT, "INR",
                AMOUNT.multiply(new BigDecimal("2")), CheckoutStatus.PAID,
                List.of(ORDER, "synthetic_order_002"));

        assertThat(capture(checkout, payment())).isEqualTo(REVIEW_REQUIRED);
    }

    @Test
    void requiresTheSuccessfulPaymentOrderToBelongToTheObservedCheckout() {
        assertThat(capture(checkout(CHECKOUT, ATTEMPT.toString(), AMOUNT, "INR", AMOUNT,
                CheckoutStatus.PAID, List.of()), payment())).isEqualTo(REVIEW_REQUIRED);
        assertThat(capture(checkout(CHECKOUT, ATTEMPT.toString(), AMOUNT, "INR", AMOUNT,
                CheckoutStatus.PAID, List.of("synthetic_order_other")), payment()))
                .isEqualTo(REVIEW_REQUIRED);
        assertThat(capture(checkout(), payment("synthetic_order_other", PAYMENT, AMOUNT,
                "INR", PaymentStatus.SUCCESS, NOW))).isEqualTo(REVIEW_REQUIRED);
    }

    @Test
    void bindsTheExactRequestedOrderAndPaymentEvenWhenAnotherOrderBelongsToTheCheckout() {
        var multiOrderCheckout = checkout(CHECKOUT, ATTEMPT.toString(), AMOUNT, "INR", AMOUNT,
                CheckoutStatus.PAID, List.of(ORDER, "synthetic_order_other"));
        assertThat(capture(multiOrderCheckout, payment("synthetic_order_other", PAYMENT, AMOUNT,
                "INR", PaymentStatus.SUCCESS, NOW))).isEqualTo(REVIEW_REQUIRED);
        assertThat(capture(checkout(), payment(ORDER, "synthetic_payment_other", AMOUNT,
                "INR", PaymentStatus.SUCCESS, NOW))).isEqualTo(REVIEW_REQUIRED);
    }

    @Test
    void rejectsUnsafeOrMissingOrderAndPaymentReferences() {
        for (String reference : Arrays.asList(null, "", " leading", "trailing ", "../payment",
                "https://example.test/payment", "synthetic\npayment", "a".repeat(101))) {
            // Also put the unsafe order into the observed checkout: membership alone cannot validate it.
            if (reference != null) {
                assertThat(capture(checkout(CHECKOUT, ATTEMPT.toString(), AMOUNT, "INR", AMOUNT,
                        CheckoutStatus.PAID, List.of(reference)), payment(reference, PAYMENT, AMOUNT,
                        "INR", PaymentStatus.SUCCESS, NOW))).isEqualTo(REVIEW_REQUIRED);
            } else {
                assertThat(capture(checkout(), payment(null, PAYMENT, AMOUNT, "INR",
                        PaymentStatus.SUCCESS, NOW))).isEqualTo(REVIEW_REQUIRED);
            }
            assertThat(capture(checkout(), payment(ORDER, reference, AMOUNT, "INR",
                    PaymentStatus.SUCCESS, NOW))).isEqualTo(REVIEW_REQUIRED);
        }
    }

    @Test
    void paidCheckoutAloneNeverMakesPendingFailedOrDroppedPaymentSuccessful() {
        for (PaymentStatus status : PaymentStatus.values()) {
            if (status == PaymentStatus.SUCCESS) continue;
            assertThat(capture(checkout(), payment(ORDER, PAYMENT, AMOUNT, "INR", status, null)))
                    .as("Synthetic provider payment status %s", status).isEqualTo(NOT_SUCCESSFUL);
        }
    }

    @Test
    void successfulButUncapturedPaymentIsNotVerifiedEvenWhenTheCheckoutIsPaid() {
        var observed = new PaymentObservation(ORDER, PAYMENT, AMOUNT, "INR",
                PaymentStatus.SUCCESS, false, NOW.minusSeconds(60), "upi");

        assertThat(capture(checkout(), observed)).isEqualTo(NOT_SUCCESSFUL);
    }

    @Test
    void successfulPaymentWithoutPaidCheckoutRequiresReviewIncludingExpiredCheckout() {
        for (CheckoutStatus status : CheckoutStatus.values()) {
            if (status == CheckoutStatus.PAID) continue;
            assertThat(capture(checkout(CHECKOUT, ATTEMPT.toString(), AMOUNT, "INR", AMOUNT,
                    status, List.of(ORDER)), payment()))
                    .as("Synthetic checkout status %s", status).isEqualTo(REVIEW_REQUIRED);
        }
    }

    @Test
    void mismatchesCannotHideBehindAnOtherwiseHarmlessPendingState() {
        assertThat(capture(checkout("synthetic_checkout_other", ATTEMPT.toString(), AMOUNT,
                "INR", BigDecimal.ZERO, CheckoutStatus.PENDING, List.of(ORDER)),
                payment(ORDER, PAYMENT, AMOUNT, "INR", PaymentStatus.PENDING, null)))
                .isEqualTo(REVIEW_REQUIRED);
    }

    @Test
    void successfulCaptureRequiresNonfutureProviderPaymentTime() {
        assertThat(capture(checkout(), payment(ORDER, PAYMENT, AMOUNT, "INR",
                PaymentStatus.SUCCESS, null))).isEqualTo(REVIEW_REQUIRED);
        assertThat(capture(checkout(), payment(ORDER, PAYMENT, AMOUNT, "INR",
                PaymentStatus.SUCCESS, NOW.plusNanos(1)))).isEqualTo(REVIEW_REQUIRED);
        assertThat(capture(checkout(), payment(ORDER, PAYMENT, AMOUNT, "INR",
                PaymentStatus.SUCCESS, NOW))).isEqualTo(VERIFIED_SUCCESS);
    }

    @Test
    void captureRequiresTrustedExpectedEvidenceAndObservationTime() {
        assertThatThrownBy(() -> GatewayPaymentEvidence.capture(null, checkout(), payment(), NOW))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> GatewayPaymentEvidence.capture(expected(), checkout(), payment(), null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void verifiesExactSuccessfulPartialAndFullRefundEvidenceWithoutMutatingAReceipt() {
        for (BigDecimal amount : List.of(new BigDecimal("250.00"), AMOUNT)) {
            var expected = new RefundRequest(REFUND, ORDER, PAYMENT, amount);
            var observed = refund(REFUND.toString(), ORDER, PAYMENT, amount,
                    RefundStatus.SUCCESS, NOW.minusSeconds(10));

            assertThat(GatewayPaymentEvidence.refund(expected, observed, NOW))
                    .isEqualTo(RefundDecision.VERIFIED_SUCCESS);
            assertThat(expected.amount()).isEqualTo(amount);
            assertThat(observed.status()).isEqualTo(RefundStatus.SUCCESS);
        }
    }

    @Test
    void refundMustMatchTheDurableRefundOrderAndPaymentReferences() {
        var expected = refundRequest();
        for (String reference : Arrays.asList(null, "", "synthetic_other", " leading", "../refund")) {
            assertThat(refundDecision(expected, refund(reference, ORDER, PAYMENT, AMOUNT,
                    RefundStatus.SUCCESS, NOW))).isEqualTo(RefundDecision.REVIEW_REQUIRED);
            assertThat(refundDecision(expected, refund(REFUND.toString(), reference, PAYMENT,
                    AMOUNT, RefundStatus.SUCCESS, NOW))).isEqualTo(RefundDecision.REVIEW_REQUIRED);
            assertThat(refundDecision(expected, refund(REFUND.toString(), ORDER, reference,
                    AMOUNT, RefundStatus.SUCCESS, NOW))).isEqualTo(RefundDecision.REVIEW_REQUIRED);
        }
    }

    @Test
    void refundCannotSilentlyChangeTheApprovedAmountOrPrecision() {
        for (BigDecimal amount : Arrays.asList(null, BigDecimal.ZERO, new BigDecimal("-1000.00"),
                new BigDecimal("999.99"), new BigDecimal("1000.01"), new BigDecimal("1000.000"))) {
            assertThat(refundDecision(refundRequest(), refund(REFUND.toString(), ORDER, PAYMENT,
                    amount, RefundStatus.SUCCESS, NOW))).isEqualTo(RefundDecision.REVIEW_REQUIRED);
        }
        assertThat(refundDecision(refundRequest(), refund(REFUND.toString(), ORDER, PAYMENT,
                new BigDecimal("1000"), RefundStatus.SUCCESS, NOW)))
                .isEqualTo(RefundDecision.VERIFIED_SUCCESS);
    }

    @Test
    void refundMustUseTheExactInrCurrencyEvenWhenTheAmountAndIdentifiersMatch() {
        for (String currency : Arrays.asList(null, "", "inr", "INR ", "USD", "RUPEES")) {
            var observed = new RefundObservation(REFUND.toString(), ORDER, PAYMENT, AMOUNT,
                    currency, RefundStatus.SUCCESS, NOW.minusSeconds(10));

            assertThat(refundDecision(refundRequest(), observed))
                    .isEqualTo(RefundDecision.REVIEW_REQUIRED);
        }
    }

    @Test
    void pendingFailedCancelledAndHeldRefundsAreNotEconomicReversals() {
        for (RefundStatus status : RefundStatus.values()) {
            if (status == RefundStatus.SUCCESS) continue;
            assertThat(refundDecision(refundRequest(), refund(REFUND.toString(), ORDER, PAYMENT,
                    AMOUNT, status, null)))
                    .as("Synthetic refund status %s", status).isEqualTo(RefundDecision.NOT_SUCCESSFUL);
        }
    }

    @Test
    void missingOrMismatchedRefundEvidenceRequiresReviewEvenWhenPending() {
        assertThat(refundDecision(refundRequest(), null)).isEqualTo(RefundDecision.REVIEW_REQUIRED);
        assertThat(refundDecision(refundRequest(), refund(REFUND.toString(), ORDER, PAYMENT,
                AMOUNT, null, NOW))).isEqualTo(RefundDecision.REVIEW_REQUIRED);
        assertThat(refundDecision(refundRequest(), refund(REFUND.toString(), ORDER, PAYMENT,
                new BigDecimal("250.00"), RefundStatus.PENDING, null)))
                .isEqualTo(RefundDecision.REVIEW_REQUIRED);
    }

    @Test
    void successfulRefundRequiresNonfutureProviderProcessingTime() {
        assertThat(refundDecision(refundRequest(), refund(REFUND.toString(), ORDER, PAYMENT,
                AMOUNT, RefundStatus.SUCCESS, null))).isEqualTo(RefundDecision.REVIEW_REQUIRED);
        assertThat(refundDecision(refundRequest(), refund(REFUND.toString(), ORDER, PAYMENT,
                AMOUNT, RefundStatus.SUCCESS, NOW.plusNanos(1))))
                .isEqualTo(RefundDecision.REVIEW_REQUIRED);
    }

    @Test
    void refundRequiresTrustedExpectedEvidenceAndObservationTime() {
        var observed = refund(REFUND.toString(), ORDER, PAYMENT, AMOUNT, RefundStatus.SUCCESS, NOW);
        assertThatThrownBy(() -> GatewayPaymentEvidence.refund(null, observed, NOW))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> GatewayPaymentEvidence.refund(refundRequest(), observed, null))
                .isInstanceOf(NullPointerException.class);
    }

    private static ExpectedPayment expected() {
        return new ExpectedPayment(ATTEMPT, CHECKOUT, ORDER, PAYMENT, AMOUNT, "INR");
    }

    private static CheckoutObservation checkout() {
        return checkout(CHECKOUT, ATTEMPT.toString(), AMOUNT, "INR", AMOUNT,
                CheckoutStatus.PAID, List.of(ORDER));
    }

    private static CheckoutObservation checkout(String checkout, String attempt, BigDecimal amount,
            String currency, BigDecimal paid, CheckoutStatus status, List<String> orders) {
        return new CheckoutObservation(checkout, attempt, amount, currency, paid, status,
                NOW.plusSeconds(300), orders);
    }

    private static PaymentObservation payment() {
        return payment(ORDER, PAYMENT, AMOUNT, "INR", PaymentStatus.SUCCESS, NOW.minusSeconds(60));
    }

    private static PaymentObservation payment(String order, String payment, BigDecimal amount,
            String currency, PaymentStatus status, Instant paidAt) {
        return new PaymentObservation(order, payment, amount, currency, status, true, paidAt, "upi");
    }

    private static GatewayPaymentEvidence.CaptureDecision capture(CheckoutObservation checkout,
            PaymentObservation payment) {
        return GatewayPaymentEvidence.capture(expected(), checkout, payment, NOW);
    }

    private static RefundRequest refundRequest() {
        return new RefundRequest(REFUND, ORDER, PAYMENT, AMOUNT);
    }

    private static RefundObservation refund(String reference, String order, String payment,
            BigDecimal amount, RefundStatus status, Instant processedAt) {
        return new RefundObservation(reference, order, payment, amount, "INR", status, processedAt);
    }

    private static RefundDecision refundDecision(RefundRequest expected, RefundObservation observed) {
        return GatewayPaymentEvidence.refund(expected, observed, NOW);
    }
}
