package com.educore.api.finance;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Provider-neutral boundary for a future hosted checkout. Implementations must return a provider-hosted
 * HTTPS redirect and must never accept PAN, CVV, bank credentials, or other payment authentication data.
 */
public interface PaymentProviderAdapter {
    String providerCode();

    /**
     * Historical discovery boundary. A gateway cannot safely create an order from
     * an account ID alone: callers must first persist a unique, scoped attempt.
     */
    @Deprecated(forRemoval = false)
    HostedCheckout createHostedCheckout(UUID accountId, BigDecimal amount, String currencyCode, URI returnUri);

    HostedCheckout createHostedCheckout(CheckoutRequest request);

    CheckoutObservation fetchCheckout(String checkoutReference);

    /** Read-only recovery of the persisted checkout; an unknown outcome must never create another checkout. */
    HostedCheckoutLookup lookupHostedCheckout(String checkoutReference);

    PaymentObservation fetchPayment(String orderReference, String paymentReference);

    /** Discover authenticated payments even when the browser closes and no webhook arrives. */
    List<PaymentObservation> fetchPayments(String orderReference);

    /** Cancellation is an observed provider state, never an instruction to release school funds. */
    CheckoutObservation cancelCheckout(CancellationRequest request);

    RefundObservation requestRefund(RefundRequest request);

    RefundObservation fetchRefund(String orderReference, String refundReference);

    /** Verify the original bytes before parsing; never accept a browser success claim. */
    VerifiedWebhook verifyWebhook(byte[] rawBody, String signature, String timestamp);

    record HostedCheckout(String providerOrderReference, URI redirectUri) {
        @Override public String toString() { return "HostedCheckout[sensitive hosted redirect]"; }
    }

    /**
     * Exact provider observation and, when supplied, its validated existing hosted redirect.
     * A redirect may be absent only for PAID, CANCELLED or EXPIRED links. Its presence
     * is not permission to pay or proof of a fee receipt; callers must inspect the observation.
     */
    record HostedCheckoutLookup(CheckoutObservation observation, Optional<HostedCheckout> hostedCheckout) {
        public HostedCheckoutLookup {
            Objects.requireNonNull(observation, "Checkout observation required");
            Objects.requireNonNull(hostedCheckout, "Explicit hosted checkout presence required");
            hostedCheckout.ifPresent(checkout -> Objects.requireNonNull(checkout.redirectUri(), "Recovered hosted redirect required"));
            if (hostedCheckout.isPresent()
                    && !Objects.equals(observation.checkoutReference(), hostedCheckout.get().providerOrderReference())) {
                throw new IllegalArgumentException("Recovered checkout must match its observation");
            }
            if (hostedCheckout.isEmpty() && observation.status() != CheckoutStatus.PAID
                    && observation.status() != CheckoutStatus.CANCELLED && observation.status() != CheckoutStatus.EXPIRED) {
                throw new IllegalArgumentException("Non-terminal checkout requires an existing hosted redirect");
            }
        }

        @Override public String toString() { return "HostedCheckoutLookup[sensitive hosted checkout observation]"; }
    }

    /** Durable attempt identity comes from the fee domain, never from the browser. */
    record CheckoutRequest(UUID attemptId, UUID accountId, BigDecimal amount,
            String currencyCode, Instant expiresAt, URI returnUri, String payerPhone) {
        public CheckoutRequest {
            Objects.requireNonNull(attemptId, "Persisted attempt required");
            Objects.requireNonNull(accountId, "Scoped fee account required");
            requireAmount(amount);
            if (!"INR".equals(currencyCode)) throw new IllegalArgumentException("INR required");
            Objects.requireNonNull(expiresAt, "Explicit expiry required");
            Objects.requireNonNull(returnUri, "Configured return address required");
        }

        @Override public String toString() { return "CheckoutRequest[sensitive payment command]"; }
    }

    /** Provider state is an observation, NOT a posted school fee payment. */
    enum CheckoutStatus { CREATED, PENDING, PARTIALLY_PAID, PAID, CANCELLED, EXPIRED }

    enum PaymentStatus { CREATED, PENDING, SUCCESS, FAILED, USER_DROPPED, CANCELLED, VOID }

    enum RefundStatus { PENDING, SUCCESS, FAILED, CANCELLED, ON_HOLD }

    record CheckoutObservation(String checkoutReference, String attemptReference,
            BigDecimal amount, String currencyCode, BigDecimal amountPaid,
            CheckoutStatus status, Instant expiresAt, List<String> orderReferences) {
        public CheckoutObservation {
            orderReferences = List.copyOf(orderReferences);
        }
    }

    record PaymentObservation(String orderReference, String paymentReference,
            BigDecimal amount, String currencyCode, PaymentStatus status,
            boolean captured, Instant providerPaidAt, String paymentMethod) {}

    record RefundRequest(UUID refundId, String orderReference, String paymentReference, BigDecimal amount) {
        public RefundRequest {
            Objects.requireNonNull(refundId, "Persisted refund request required");
            requireReference(orderReference);
            requireReference(paymentReference);
            requireAmount(amount);
        }
        @Override public String toString() { return "RefundRequest[private approved provider command]"; }
    }

    record CancellationRequest(UUID cancellationId, String checkoutReference) {
        public CancellationRequest {
            Objects.requireNonNull(cancellationId, "Persisted cancellation required");
            requireReference(checkoutReference);
        }
    }

    record RefundObservation(String refundReference, String orderReference,
            String paymentReference, BigDecimal amount, String currencyCode, RefundStatus status,
            Instant processedAt) {}

    /** Minimal authenticated routing evidence; raw customer/card payloads are not retained. */
    record VerifiedWebhook(String providerCode, String bodySha256, String eventType,
            String checkoutReference, String orderReference, String paymentReference,
            String refundReference) {}

    static void requireAmount(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0 || amount.scale() > 2
                || amount.compareTo(new BigDecimal("100000000.00")) > 0) {
            throw new IllegalArgumentException("Exact positive amount with at most two decimals required");
        }
    }

    static String requireReference(String value) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,99}")) {
            throw new IllegalArgumentException("Canonical provider reference required");
        }
        return value;
    }
}
