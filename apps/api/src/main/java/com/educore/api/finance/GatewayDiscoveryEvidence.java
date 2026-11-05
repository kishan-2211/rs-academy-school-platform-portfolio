package com.educore.api.finance;

import static com.educore.api.finance.PaymentProviderAdapter.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Pure classification of independently authenticated, complete provider GETs.
 * Not a browser DTO, authentication boundary, posting approval or reservation-release authority.
 * A processor must recheck the persisted attempt, trusted credential binding and current lease
 * before using these facts. Incomplete discovery must never freeze an immutable capture proof.
 */
public final class GatewayDiscoveryEvidence {
    private static final int MAX_ORDERS = 100;
    private static final int MAX_PAYMENTS_PER_ORDER = 100;
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("100000000.00");

    private GatewayDiscoveryEvidence() {}

    /** Immutable server intent; account eligibility and financial-day controls remain database checks. */
    public record ExpectedAttempt(UUID attemptId, UUID accountId, GatewayWorkQueue.ProviderBinding binding,
            BigDecimal amount, String currencyCode, Instant createdAt, Instant expiresAt, String schoolTimeZone) {
        public ExpectedAttempt {
            try {
                if (attemptId == null || accountId == null || binding == null || createdAt == null || expiresAt == null
                        || !"INR".equals(currencyCode) || !expiresAt.isAfter(createdAt)
                        || expiresAt.isAfter(createdAt.plusSeconds(86400))) {
                    throw invalidIntent();
                }
                requireAmount(amount);
                ZoneId.of(schoolTimeZone);
            } catch (RuntimeException invalid) {
                throw invalidIntent();
            }
        }

        @Override public String toString() { return "ExpectedAttempt[internal scoped gateway intent]"; }
    }

    /** Unknown/failed/budget-interrupted reads are explicit, never a synthetic empty payment list. */
    public static final class OrderRead {
        private final String orderReference;
        private final Optional<List<PaymentObservation>> payments;

        private OrderRead(String orderReference, Optional<List<PaymentObservation>> payments) {
            requireReference(orderReference);
            this.orderReference = orderReference;
            this.payments = payments.map(values -> {
                if (values.size() > MAX_PAYMENTS_PER_ORDER || values.stream().anyMatch(Objects::isNull)) {
                    throw invalidDiscovery();
                }
                return List.copyOf(values);
            });
        }

        /** Only after the adapter's entire untruncated list GET succeeds. */
        public static OrderRead complete(String orderReference, List<PaymentObservation> payments) {
            if (payments == null) throw invalidDiscovery();
            return new OrderRead(orderReference, Optional.of(payments));
        }

        public static OrderRead unknown(String orderReference) {
            return new OrderRead(orderReference, Optional.empty());
        }

        public String orderReference() { return orderReference; }
        public Optional<List<PaymentObservation>> payments() { return payments; }
        @Override public String toString() { return "OrderRead[internal authenticated discovery outcome]"; }
    }

    /** Binding is the trusted route actually used for these GETs, not a client-supplied label. */
    public record Discovery(GatewayWorkQueue.ProviderBinding binding, CheckoutObservation checkout,
            List<OrderRead> orderReads, Instant observedAt) {
        public Discovery {
            if (binding == null || orderReads == null || observedAt == null || orderReads.size() > MAX_ORDERS
                    || orderReads.stream().anyMatch(Objects::isNull)) {
                throw invalidDiscovery();
            }
            orderReads = List.copyOf(orderReads);
        }

        @Override public String toString() { return "Discovery[internal scoped gateway GET evidence]"; }
    }

    public enum Disposition {
        INCOMPLETE, PENDING, EXACT_CAPTURE, REVIEW_REQUIRED, TERMINAL_ZERO_OBSERVED
    }

    /** Exact candidate only; database classification/close evidence, capture id and posting are not invented here. */
    public record CaptureCandidate(PaymentObservation payment, LocalDate originalBusinessDate) {
        public CaptureCandidate {
            Objects.requireNonNull(payment);
            Objects.requireNonNull(originalBusinessDate);
        }

        @Override public String toString() { return "CaptureCandidate[unposted exact gateway evidence]"; }
    }

    /** Only classify can construct a result. It never accepts caller-asserted discovery/count flags. */
    public static final class Result {
        private final ExpectedAttempt expected;
        private final Instant observedAt;
        private final Optional<CheckoutObservation> checkout;
        private final List<PaymentObservation> paymentFacts;
        private final boolean discoveryComplete;
        private final int pendingPaymentCount;
        private final int successfulPaymentCount;
        private final Disposition disposition;
        private final Optional<CaptureCandidate> exactCapture;

        private Result(ExpectedAttempt expected, Instant observedAt, CheckoutObservation checkout,
                List<PaymentObservation> paymentFacts, boolean discoveryComplete, int pendingPaymentCount,
                int successfulPaymentCount, Disposition disposition, CaptureCandidate exactCapture) {
            this.expected = expected;
            this.observedAt = observedAt;
            this.checkout = Optional.ofNullable(checkout);
            this.paymentFacts = List.copyOf(paymentFacts);
            this.discoveryComplete = discoveryComplete;
            this.pendingPaymentCount = pendingPaymentCount;
            this.successfulPaymentCount = successfulPaymentCount;
            this.disposition = disposition;
            this.exactCapture = Optional.ofNullable(exactCapture);
        }

        public ExpectedAttempt expected() { return expected; }
        public Instant observedAt() { return observedAt; }
        public Optional<CheckoutObservation> checkout() { return checkout; }
        public List<PaymentObservation> paymentFacts() { return paymentFacts; }
        public boolean discoveryComplete() { return discoveryComplete; }
        public int pendingPaymentCount() { return pendingPaymentCount; }
        public int successfulPaymentCount() { return successfulPaymentCount; }
        public Disposition disposition() { return disposition; }
        public Optional<CaptureCandidate> exactCapture() { return exactCapture; }
        @Override public String toString() { return "Result[unposted gateway discovery classification]"; }
    }

    public static Result classify(ExpectedAttempt expected, Discovery discovery) {
        Objects.requireNonNull(expected, "Persisted gateway intent required");
        Objects.requireNonNull(discovery, "Authenticated provider discovery required");
        Instant observedAt = discovery.observedAt();
        // Do not attribute another school's/merchant's facts to this intent, even when IDs look alike.
        if (!expected.binding().equals(discovery.binding()) || observedAt.isBefore(expected.createdAt())) {
            return empty(expected, observedAt, Disposition.REVIEW_REQUIRED);
        }
        CheckoutObservation checkout = discovery.checkout();
        if (checkout == null) return empty(expected, observedAt, Disposition.INCOMPLETE);
        if (!exactCheckout(expected, checkout)) return empty(expected, observedAt, Disposition.REVIEW_REQUIRED);

        Set<String> orders = Set.copyOf(checkout.orderReferences());
        Set<String> readOrders = new HashSet<>();
        Set<String> paymentIdentities = new HashSet<>();
        var facts = new LinkedHashMap<PaymentObservation, PaymentObservation>();
        boolean complete = true;
        boolean contradiction = checkout.amountPaid().compareTo(expected.amount()) > 0
                || (checkout.status() == CheckoutStatus.CREATED || checkout.status() == CheckoutStatus.PENDING)
                    && checkout.amountPaid().signum() != 0
                || checkout.status() == CheckoutStatus.PAID && !sameMoney(expected.amount(), checkout.amountPaid())
                || checkout.status() == CheckoutStatus.PARTIALLY_PAID
                    && (checkout.amountPaid().signum() == 0 || checkout.amountPaid().compareTo(expected.amount()) >= 0)
                || (checkout.status() == CheckoutStatus.CANCELLED || checkout.status() == CheckoutStatus.EXPIRED)
                    && checkout.amountPaid().signum() != 0;
        for (OrderRead read : discovery.orderReads()) {
            if (!orders.contains(read.orderReference())) {
                complete = false;
                contradiction = true;
                continue; // Never attach foreign-order facts to this attempt.
            }
            boolean uniqueRead = readOrders.add(read.orderReference());
            if (!uniqueRead) {
                complete = false;
                contradiction = true;
            }
            if (read.payments().isEmpty()) {
                complete = false;
                continue;
            }
            for (PaymentObservation payment : read.payments().get()) {
                if (!minimalPayment(payment, observedAt) || !read.orderReference().equals(payment.orderReference())) {
                    complete = false;
                    contradiction = true;
                    continue;
                }
                // Distinct authentic facts survive contradictory/repeated reads; neither wins by arrival order.
                facts.putIfAbsent(payment, payment);
                if (!paymentIdentities.add(payment.paymentReference())) {
                    complete = false;
                    contradiction = true;
                }
                if (!sameMoney(expected.amount(), payment.amount()) || !expected.currencyCode().equals(payment.currencyCode())
                        || payment.providerPaidAt() != null && payment.providerPaidAt().isBefore(expected.createdAt())) {
                    contradiction = true;
                }
                if (payment.status() != PaymentStatus.SUCCESS && payment.captured()) {
                    // A FAILED/captured response is not harmless evidence of zero money.
                    contradiction = true;
                }
            }
        }
        complete &= readOrders.equals(orders);

        // Count unique identities by every retained status, not whichever conflicting response arrived first.
        var successfulIdentities = new HashSet<String>();
        var pendingIdentities = new HashSet<String>();
        var successes = new ArrayList<PaymentObservation>();
        for (PaymentObservation payment : facts.values()) {
            if (payment.status() == PaymentStatus.SUCCESS) successfulIdentities.add(payment.paymentReference());
            if (payment.status() == PaymentStatus.SUCCESS && payment.captured()) successes.add(payment);
            if (payment.status() != PaymentStatus.SUCCESS && payment.captured()
                    || payment.status() == PaymentStatus.CREATED || payment.status() == PaymentStatus.PENDING
                    || payment.status() == PaymentStatus.SUCCESS && !payment.captured()) {
                pendingIdentities.add(payment.paymentReference());
            }
        }
        int pending = pendingIdentities.size();
        int successful = successfulIdentities.size();

        Disposition disposition;
        CaptureCandidate candidate = null;
        if (contradiction) {
            disposition = Disposition.REVIEW_REQUIRED;
        } else if (!complete) {
            disposition = Disposition.INCOMPLETE;
        } else if (successful > 1) {
            disposition = Disposition.REVIEW_REQUIRED;
        } else if (pending > 0) {
            disposition = Disposition.PENDING;
        } else if (successes.size() == 1) {
            PaymentObservation payment = successes.getFirst();
            var exact = new GatewayPaymentEvidence.ExpectedPayment(expected.attemptId(), expected.attemptId().toString(),
                    payment.orderReference(), payment.paymentReference(), expected.amount(), expected.currencyCode());
            if (GatewayPaymentEvidence.capture(exact, checkout, payment, observedAt)
                    == GatewayPaymentEvidence.CaptureDecision.VERIFIED_SUCCESS) {
                candidate = new CaptureCandidate(payment, payment.providerPaidAt()
                        .atZone(ZoneId.of(expected.schoolTimeZone())).toLocalDate());
                disposition = Disposition.EXACT_CAPTURE;
            } else {
                disposition = Disposition.REVIEW_REQUIRED;
            }
        } else if (checkout.amountPaid().signum() != 0 || checkout.status() == CheckoutStatus.PAID
                || checkout.status() == CheckoutStatus.PARTIALLY_PAID) {
            disposition = Disposition.REVIEW_REQUIRED;
        } else if (checkout.status() == CheckoutStatus.CANCELLED || checkout.status() == CheckoutStatus.EXPIRED) {
            // This is only an observed snapshot. Human release also checks all previously retained money facts.
            disposition = Disposition.TERMINAL_ZERO_OBSERVED;
        } else {
            disposition = Disposition.PENDING;
        }
        return new Result(expected, observedAt, checkout, List.copyOf(facts.values()), complete, pending,
                successful, disposition, candidate);
    }

    private static boolean exactCheckout(ExpectedAttempt expected, CheckoutObservation observed) {
        if (!expected.attemptId().toString().equals(observed.checkoutReference())
                || !expected.attemptId().toString().equals(observed.attemptReference())
                || !sameMoney(expected.amount(), observed.amount()) || !expected.currencyCode().equals(observed.currencyCode())
                || observed.amountPaid() == null || observed.amountPaid().scale() > 2 || observed.amountPaid().signum() < 0
                || observed.amountPaid().compareTo(MAX_AMOUNT) > 0 || observed.status() == null
                || !expected.expiresAt().equals(observed.expiresAt()) || observed.orderReferences().size() > MAX_ORDERS) {
            return false;
        }
        var unique = new HashSet<String>();
        for (String reference : observed.orderReferences()) {
            try { requireReference(reference); }
            catch (IllegalArgumentException invalid) { return false; }
            if (!unique.add(reference)) return false;
        }
        return true;
    }

    private static boolean minimalPayment(PaymentObservation payment, Instant observedAt) {
        try {
            GatewayWorkQueue.validatePayment(payment, observedAt);
            return true;
        } catch (IllegalArgumentException | NullPointerException invalid) {
            return false;
        }
    }

    private static boolean sameMoney(BigDecimal expected, BigDecimal observed) {
        return observed != null && observed.scale() <= 2 && expected.compareTo(observed) == 0;
    }

    private static Result empty(ExpectedAttempt expected, Instant observedAt, Disposition disposition) {
        return new Result(expected, observedAt, null, List.of(), false, 0, 0, disposition, null);
    }

    private static IllegalArgumentException invalidIntent() {
        return new IllegalArgumentException("Exact immutable scoped gateway intent required");
    }

    private static IllegalArgumentException invalidDiscovery() {
        return new IllegalArgumentException("Bounded authenticated gateway discovery required");
    }
}
