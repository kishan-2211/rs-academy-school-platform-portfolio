package com.educore.api.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.educore.api.finance.GatewayPaymentEvidence.ExpectedPayment;
import com.educore.api.finance.PaymentProviderAdapter.*;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Constructor and confidentiality guards only; these fixtures do not contact a provider. */
class PaymentProviderRequestTest {
    private static final UUID ATTEMPT = UUID.fromString("00000000-0000-4000-8000-000000000201");
    private static final UUID ACCOUNT = UUID.fromString("00000000-0000-4000-8000-000000000202");
    private static final UUID REFUND = UUID.fromString("00000000-0000-4000-8000-000000000203");
    private static final Instant EXPIRY = Instant.parse("2026-10-10T06:35:00Z");
    private static final URI RETURN_URI = URI.create("https://school.example.test/family/payment-return");
    private static final String SYNTHETIC_PHONE = "0000000000";

    @Test
    void acceptsExactPositiveInrCommandsAndCanonicalDurableReferences() {
        for (String input : List.of("0.01", "1", "1000.00", "100000000.00")) {
            var amount = new BigDecimal(input);
            assertThat(checkout(ATTEMPT, ACCOUNT, amount, "INR", EXPIRY, RETURN_URI).amount())
                    .isEqualTo(amount);
            assertThat(new ExpectedPayment(ATTEMPT, "synthetic_checkout_001", "synthetic_order_001",
                    "synthetic_payment_001", amount, "INR").amount())
                    .isEqualTo(amount);
            assertThat(new RefundRequest(REFUND, "synthetic_order_001", "synthetic_payment_001", amount)
                    .amount()).isEqualTo(amount);
        }
        for (String reference : List.of("0", "synthetic-provider_001", "a".repeat(100))) {
            assertThat(new ExpectedPayment(ATTEMPT, reference, reference, reference, new BigDecimal("1.00"), "INR")
                    .checkoutReference()).isEqualTo(reference);
            assertThat(new RefundRequest(REFUND, reference, reference, new BigDecimal("1.00"))
                    .orderReference()).isEqualTo(reference);
        }
    }

    @Test
    void rejectsNullZeroNegativeExcessiveAndOverprecisionCommandAmounts() {
        for (BigDecimal amount : Arrays.asList(null, BigDecimal.ZERO, new BigDecimal("-0.01"),
                new BigDecimal("100000000.01"), new BigDecimal("0.001"), new BigDecimal("1000.000"))) {
            assertThatThrownBy(() -> checkout(ATTEMPT, ACCOUNT, amount, "INR", EXPIRY, RETURN_URI))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new ExpectedPayment(ATTEMPT, "synthetic_checkout_001", "synthetic_order_001",
                    "synthetic_payment_001", amount, "INR"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new RefundRequest(REFUND, "synthetic_order_001", "synthetic_payment_001", amount))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void rejectsUnsupportedOrNoncanonicalCurrencies() {
        for (String currency : Arrays.asList(null, "", "inr", "INR ", "USD", "RUPEES")) {
            assertThatThrownBy(() -> checkout(ATTEMPT, ACCOUNT, new BigDecimal("1.00"), currency,
                    EXPIRY, RETURN_URI)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new ExpectedPayment(ATTEMPT, "synthetic_checkout_001",
                    "synthetic_order_001", "synthetic_payment_001", new BigDecimal("1.00"), currency))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void requiresPersistedAttemptScopedAccountExpiryAndConfiguredReturnAddress() {
        var amount = new BigDecimal("1.00");
        assertThatThrownBy(() -> checkout(null, ACCOUNT, amount, "INR", EXPIRY, RETURN_URI))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> checkout(ATTEMPT, null, amount, "INR", EXPIRY, RETURN_URI))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> checkout(ATTEMPT, ACCOUNT, amount, "INR", null, RETURN_URI))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> checkout(ATTEMPT, ACCOUNT, amount, "INR", EXPIRY, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ExpectedPayment(null, "synthetic_checkout_001", "synthetic_order_001",
                "synthetic_payment_001", amount, "INR"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RefundRequest(null, "synthetic_order_001", "synthetic_payment_001", amount))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectsUnsafeMissingAndOversizedProviderReferences() {
        for (String reference : Arrays.asList(null, "", " synthetic", "synthetic ", "../payment",
                "https://example.test/payment", "synthetic\npayment", "synthetic.payment",
                "-synthetic", "_synthetic", "a".repeat(101))) {
            assertThatThrownBy(() -> new ExpectedPayment(ATTEMPT, reference, "synthetic_order_001",
                    "synthetic_payment_001", new BigDecimal("1.00"), "INR"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new ExpectedPayment(ATTEMPT, "synthetic_checkout_001", reference,
                    "synthetic_payment_001", new BigDecimal("1.00"), "INR"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new ExpectedPayment(ATTEMPT, "synthetic_checkout_001", "synthetic_order_001",
                    reference, new BigDecimal("1.00"), "INR"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new RefundRequest(REFUND, reference, "synthetic_payment_001",
                    new BigDecimal("1.00"))).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new RefundRequest(REFUND, "synthetic_order_001", reference,
                    new BigDecimal("1.00"))).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void checkoutCommandToStringDoesNotRevealPayerOrAccountInformation() {
        var request = checkout(ATTEMPT, ACCOUNT, new BigDecimal("1000.00"), "INR", EXPIRY, RETURN_URI);

        assertThat(request.toString()).isEqualTo("CheckoutRequest[sensitive payment command]")
                .doesNotContain(SYNTHETIC_PHONE, ATTEMPT.toString(), ACCOUNT.toString(),
                        RETURN_URI.toString(), "1000", EXPIRY.toString());
    }

    @Test
    void checkoutObservationPreservesAnImmutableCopyOfServerOrderReferences() {
        var input = new ArrayList<>(List.of("synthetic_order_001"));
        var observed = new CheckoutObservation("synthetic_checkout_001", ATTEMPT.toString(),
                new BigDecimal("1.00"), "INR", BigDecimal.ZERO, CheckoutStatus.CREATED, EXPIRY, input);
        input.add("synthetic_order_other");

        assertThat(observed.orderReferences()).containsExactly("synthetic_order_001");
        assertThatThrownBy(() -> observed.orderReferences().add("synthetic_order_other"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private static CheckoutRequest checkout(UUID attempt, UUID account, BigDecimal amount,
            String currency, Instant expiresAt, URI returnUri) {
        return new CheckoutRequest(attempt, account, amount, currency, expiresAt, returnUri, SYNTHETIC_PHONE);
    }
}
