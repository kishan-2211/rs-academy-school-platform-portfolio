package com.educore.api.finance;

import static com.educore.api.finance.PaymentProviderAdapter.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Exact comparison between a persisted school attempt and authenticated provider
 * observations. This class cannot write fees, issue receipts, or change an order.
 */
public final class GatewayPaymentEvidence {
    private GatewayPaymentEvidence() {}

    public record ExpectedPayment(UUID attemptId, String checkoutReference, String orderReference, String paymentReference,
            BigDecimal amount, String currencyCode) {
        public ExpectedPayment {
            Objects.requireNonNull(attemptId, "Persisted attempt required");
            requireReference(checkoutReference);
            requireReference(orderReference);
            requireReference(paymentReference);
            requireAmount(amount);
            if (!"INR".equals(currencyCode)) throw new IllegalArgumentException("INR required");
        }
    }

    public enum CaptureDecision { VERIFIED_SUCCESS, NOT_SUCCESSFUL, REVIEW_REQUIRED }

    public static CaptureDecision capture(ExpectedPayment expected, CheckoutObservation checkout,
            PaymentObservation payment, Instant now) {
        Objects.requireNonNull(expected, "Persisted evidence required");
        Objects.requireNonNull(now, "Observation time required");
        // A mismatched response must never turn into a harmless-looking pending/zero balance.
        if (checkout == null || payment == null
                || !expected.checkoutReference().equals(checkout.checkoutReference())
                || !expected.attemptId().toString().equals(checkout.attemptReference())
                || !expected.currencyCode().equals(checkout.currencyCode())
                || !expected.currencyCode().equals(payment.currencyCode())
                || !sameMoney(expected.amount(), checkout.amount())
                || !sameMoney(expected.amount(), payment.amount())
                || !expected.orderReference().equals(payment.orderReference())
                || !expected.paymentReference().equals(payment.paymentReference())
                || !validReference(payment.orderReference())
                || !validReference(payment.paymentReference())
                || !checkout.orderReferences().contains(payment.orderReference())
                || checkout.status() == null || payment.status() == null) {
            return CaptureDecision.REVIEW_REQUIRED;
        }
        if (payment.status() != PaymentStatus.SUCCESS || !payment.captured()) return CaptureDecision.NOT_SUCCESSFUL;
        // A provider PAID link alone is insufficient; the exact payment must be successful too.
        if (checkout.status() != CheckoutStatus.PAID
                || !sameMoney(expected.amount(), checkout.amountPaid())
                || payment.providerPaidAt() == null || payment.providerPaidAt().isAfter(now)) {
            return CaptureDecision.REVIEW_REQUIRED;
        }
        return CaptureDecision.VERIFIED_SUCCESS;
    }

    public enum RefundDecision { VERIFIED_SUCCESS, NOT_SUCCESSFUL, REVIEW_REQUIRED }

    public static RefundDecision refund(RefundRequest expected, RefundObservation observed, Instant now) {
        Objects.requireNonNull(expected, "Persisted refund evidence required");
        Objects.requireNonNull(now, "Observation time required");
        if (observed == null
                || !expected.refundId().toString().equals(observed.refundReference())
                || !expected.orderReference().equals(observed.orderReference())
                || !expected.paymentReference().equals(observed.paymentReference())
                || !"INR".equals(observed.currencyCode())
                || !sameMoney(expected.amount(), observed.amount()) || observed.status() == null) {
            return RefundDecision.REVIEW_REQUIRED;
        }
        if (observed.status() != RefundStatus.SUCCESS) return RefundDecision.NOT_SUCCESSFUL;
        if (observed.processedAt() == null || observed.processedAt().isAfter(now)) return RefundDecision.REVIEW_REQUIRED;
        return RefundDecision.VERIFIED_SUCCESS;
    }

    private static boolean sameMoney(BigDecimal expected, BigDecimal observed) {
        return observed != null && observed.scale() <= 2 && observed.signum() >= 0
                && expected.compareTo(observed) == 0;
    }

    private static boolean validReference(String value) {
        return value != null && value.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,99}");
    }
}
