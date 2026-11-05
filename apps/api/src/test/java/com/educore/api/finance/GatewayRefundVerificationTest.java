package com.educore.api.finance;

import static com.educore.api.finance.GatewayRefundVerification.Outcome.*;
import static org.assertj.core.api.Assertions.*;

import com.educore.api.finance.GatewayRefundVerification.Expected;
import com.educore.api.finance.GatewayRefundVerification.Observation;
import com.educore.api.finance.PaymentProviderAdapter.RefundObservation;
import com.educore.api.finance.PaymentProviderAdapter.RefundStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Pure synthetic classification only; not provider, database or refund-posting acceptance. */
class GatewayRefundVerificationTest {
    private static final UUID REFUND = uuid(1), PAYMENT = uuid(2), CAPTURE = uuid(3);
    private static final UUID ORGANISATION = uuid(4), SCHOOL = uuid(5), PROVIDER = uuid(6);
    private static final String ORDER = "synthetic_order_001", PAYMENT_REF = "synthetic_payment_001";
    private static final BigDecimal CAPTURED = new BigDecimal("1000.00"), REFUNDED = new BigDecimal("29.29");
    private static final Instant PAID = Instant.parse("2026-10-16T12:00:00.123456789Z");
    private static final Instant REQUESTED = Instant.parse("2026-10-17T18:28:00.123456789Z");
    private static final Instant APPROVED = Instant.parse("2026-10-17T18:29:00.123456789Z");
    private static final Instant PROCESSED = Instant.parse("2026-10-17T18:29:59.999999999Z");
    private static final Instant OBSERVED = Instant.parse("2026-10-17T18:30:05.123456789Z");
    private static final String ZONE = "Asia/Kolkata";
    private static final GatewayWorkQueue.ProviderBinding BINDING = binding(ORGANISATION, SCHOOL, PROVIDER,
            "CASHFREE", "TEST", "synthetic_merchant", "env:APP_SYNTHETIC_REFUND");

    @Test
    void exactPartialSuccessRetainsNanosecondsAndFloorsWithoutCrossingTheSchoolDay() {
        var result = verify(refund(RefundStatus.SUCCESS, PROCESSED));
        assertThat(result.outcome()).isEqualTo(VERIFIED_SUCCESS);
        var success = result.success().orElseThrow();
        assertThat(success.processedAtExact()).isEqualTo("2026-10-17T18:29:59.999999999Z");
        assertThat(success.processedAtProjection()).isEqualTo(Instant.parse("2026-10-17T18:29:59.999999Z"));
        assertThat(success.processedOn()).isEqualTo(LocalDate.of(2026, 10, 17));
        assertThat(result.evidenceSha256()).matches("[a-f0-9]{64}");
    }

    @Test
    void fullRefundAndSmallestPaiseBothRequireTheSameExactVerification() {
        for (String amount : List.of("0.01", "1000", "1000.00")) {
            var selected = new BigDecimal(amount);
            var expected = expected(CAPTURED, selected, PAID, REQUESTED, APPROVED, ZONE);
            var observed = new RefundObservation(REFUND.toString(), ORDER, PAYMENT_REF, selected,
                    "INR", RefundStatus.SUCCESS, PROCESSED);
            assertThat(GatewayRefundVerification.verify(expected, new Observation(BINDING, observed, OBSERVED)).outcome())
                    .isEqualTo(VERIFIED_SUCCESS);
        }
    }

    @Test
    void sourceSchoolZoneDeterminesTheDateWithoutChangingTheOriginalInstant() {
        var atMidnight = PROCESSED.plusNanos(2);
        var before = verify(refund(RefundStatus.SUCCESS, PROCESSED)).success().orElseThrow();
        var after = verify(refund(RefundStatus.SUCCESS, atMidnight)).success().orElseThrow();
        assertThat(before.processedOn()).isEqualTo(LocalDate.of(2026, 10, 17));
        assertThat(after.processedOn()).isEqualTo(LocalDate.of(2026, 10, 18));
        assertThat(after.processedAtExact()).isEqualTo(atMidnight.toString());
        var utc = expected(CAPTURED, REFUNDED, PAID, REQUESTED, APPROVED, "UTC");
        assertThat(GatewayRefundVerification.verify(utc, new Observation(BINDING, refund(RefundStatus.SUCCESS, atMidnight), OBSERVED))
                .success().orElseThrow().processedOn()).isEqualTo(LocalDate.of(2026, 10, 17));
    }

    @Test
    void everyFrozenProviderBindingComponentMustMatch() {
        var alternatives = List.of(
                binding(uuid(14), SCHOOL, PROVIDER, "CASHFREE", "TEST", "synthetic_merchant", "env:APP_SYNTHETIC_REFUND"),
                binding(ORGANISATION, uuid(15), PROVIDER, "CASHFREE", "TEST", "synthetic_merchant", "env:APP_SYNTHETIC_REFUND"),
                binding(ORGANISATION, SCHOOL, uuid(16), "CASHFREE", "TEST", "synthetic_merchant", "env:APP_SYNTHETIC_REFUND"),
                binding(ORGANISATION, SCHOOL, PROVIDER, "SYNTHETIC", "TEST", "synthetic_merchant", "env:APP_SYNTHETIC_REFUND"),
                binding(ORGANISATION, SCHOOL, PROVIDER, "CASHFREE", "LIVE", "synthetic_merchant", "env:APP_SYNTHETIC_REFUND"),
                binding(ORGANISATION, SCHOOL, PROVIDER, "CASHFREE", "TEST", "synthetic_other", "env:APP_SYNTHETIC_REFUND"),
                binding(ORGANISATION, SCHOOL, PROVIDER, "CASHFREE", "TEST", "synthetic_merchant", "env:APP_SYNTHETIC_OTHER"));
        for (var binding : alternatives) review(new Observation(binding, refund(RefundStatus.SUCCESS, PROCESSED), OBSERVED));
        review(new Observation(null, refund(RefundStatus.SUCCESS, PROCESSED), OBSERVED));
    }

    @Test
    void refundOrderAndOriginalPaymentIdentityCannotBeSubstituted() {
        for (String reference : Arrays.asList(null, "", "synthetic_other", "../foreign", " leading", "x".repeat(101))) {
            review(observation(new RefundObservation(reference, ORDER, PAYMENT_REF, REFUNDED, "INR", RefundStatus.SUCCESS, PROCESSED)));
            review(observation(new RefundObservation(REFUND.toString(), reference, PAYMENT_REF, REFUNDED, "INR", RefundStatus.SUCCESS, PROCESSED)));
            review(observation(new RefundObservation(REFUND.toString(), ORDER, reference, REFUNDED, "INR", RefundStatus.SUCCESS, PROCESSED)));
        }
    }

    @Test
    void mismatchedMissingNonpositiveOversizedAndOverprecisionAmountsRequireReview() {
        for (BigDecimal amount : Arrays.asList(null, BigDecimal.ZERO, new BigDecimal("-29.29"), new BigDecimal("29.28"),
                new BigDecimal("29.30"), new BigDecimal("29.290"), new BigDecimal("100000000.01"), new BigDecimal("1E+2147483647"))) {
            review(observation(new RefundObservation(REFUND.toString(), ORDER, PAYMENT_REF, amount, "INR", RefundStatus.SUCCESS, PROCESSED)));
        }
    }

    @Test
    void equivalentValidDecimalScalesHaveTheSameSourceIdentity() {
        var expected = expected(CAPTURED, new BigDecimal("29.00"), PAID, REQUESTED, APPROVED, ZONE);
        var hashes = new ArrayList<String>();
        for (String amount : List.of("29", "29.0", "29.00")) {
            var source = new RefundObservation(REFUND.toString(), ORDER, PAYMENT_REF, new BigDecimal(amount), "INR", RefundStatus.SUCCESS, PROCESSED);
            var result = GatewayRefundVerification.verify(expected, observation(source));
            assertThat(result.outcome()).isEqualTo(VERIFIED_SUCCESS);
            hashes.add(result.evidenceSha256());
        }
        assertThat(hashes).containsOnly(hashes.getFirst());
        var invalid = new RefundObservation(REFUND.toString(), ORDER, PAYMENT_REF, new BigDecimal("29.000"), "INR", RefundStatus.SUCCESS, PROCESSED);
        var result = GatewayRefundVerification.verify(expected, observation(invalid));
        assertThat(result.outcome()).isEqualTo(REVIEW_REQUIRED);
        assertThat(result.evidenceSha256()).isNotEqualTo(hashes.getFirst());
    }

    @Test
    void onlyTheExactInrCurrencyAndKnownStatusCanProveMoney() {
        for (String currency : Arrays.asList(null, "", "inr", "INR ", "USD")) {
            review(observation(new RefundObservation(REFUND.toString(), ORDER, PAYMENT_REF, REFUNDED, currency, RefundStatus.SUCCESS, PROCESSED)));
        }
        review(observation(new RefundObservation(REFUND.toString(), ORDER, PAYMENT_REF, REFUNDED, "INR", null, PROCESSED)));
    }

    @Test
    void preapprovalProcessingIsRejectedAtNanosecondPrecision() {
        var approved = Instant.parse("2026-10-17T18:29:59.123456789Z");
        var expected = expected(CAPTURED, REFUNDED, PAID, REQUESTED, approved, ZONE);
        var oneNanoBefore = GatewayRefundVerification.verify(expected, observation(refund(RefundStatus.SUCCESS, approved.minusNanos(1))));
        assertThat(oneNanoBefore.outcome()).isEqualTo(REVIEW_REQUIRED);
        assertThat(oneNanoBefore.success()).isEmpty();
        assertThat(GatewayRefundVerification.verify(expected, observation(refund(RefundStatus.SUCCESS, approved))).outcome())
                .isEqualTo(VERIFIED_SUCCESS);
        review(observation(refund(RefundStatus.SUCCESS, PAID.minusNanos(1))));
    }

    @Test
    void missingFutureAndUnsupportedProviderProcessingInstantsRequireReview() {
        for (Instant processed : Arrays.asList(null, OBSERVED.plusNanos(1), Instant.MIN, Instant.MAX)) {
            review(observation(refund(RefundStatus.SUCCESS, processed)));
        }
        assertThat(verify(refund(RefundStatus.SUCCESS, OBSERVED)).outcome()).isEqualTo(VERIFIED_SUCCESS);
    }

    @Test
    void supportedUtcSourceWhoseSchoolDateIsOutsideTheDomainRequiresReview() {
        var original = Instant.parse("0001-01-01T00:00:00Z");
        var expected = expected(CAPTURED, REFUNDED, original, original, original, "-18:00");
        var observed = new Observation(BINDING, refund(RefundStatus.SUCCESS, original), original.plusSeconds(1));
        assertThat(GatewayRefundVerification.verify(expected, observed).outcome()).isEqualTo(REVIEW_REQUIRED);
    }

    @Test
    void pendingFailedCancelledAndHeldFactsNeverProduceMoneyOrReleaseAuthority() {
        for (RefundStatus status : RefundStatus.values()) {
            if (status == RefundStatus.SUCCESS) continue;
            var result = verify(refund(status, null));
            assertThat(result.outcome()).isEqualTo(NOT_SUCCESSFUL);
            assertThat(result.success()).isEmpty();
        }
    }

    @Test
    void malformedFactsCannotHideBehindPendingOrFailedStatus() {
        review(observation(new RefundObservation(REFUND.toString(), ORDER, "synthetic_foreign", REFUNDED, "INR", RefundStatus.PENDING, null)));
        review(observation(new RefundObservation(REFUND.toString(), ORDER, PAYMENT_REF, BigDecimal.ZERO, "INR", RefundStatus.FAILED, null)));
        review(observation(refund(RefundStatus.PENDING, OBSERVED.plusNanos(1))));
        review(observation(refund(RefundStatus.FAILED, APPROVED.minusNanos(1))));
    }

    @Test
    void absentReadRemainsUnknownWithoutAZeroOrSuccessfulRefund() {
        var absent = GatewayRefundVerification.verify(expected(), null);
        var noFacts = GatewayRefundVerification.verify(expected(), new Observation(BINDING, null, OBSERVED));
        assertThat(absent.outcome()).isEqualTo(UNKNOWN);
        assertThat(noFacts.outcome()).isEqualTo(UNKNOWN);
        assertThat(absent.success()).isEmpty();
        assertThat(noFacts.success()).isEmpty();
        assertThat(absent.evidenceSha256()).matches("[a-f0-9]{64}");
        review(new Observation(null, null, OBSERVED));
    }

    @Test
    void invalidExpectedProvenanceIsRejectedWithoutEchoingPrivateInput() {
        for (BigDecimal money : Arrays.asList(null, BigDecimal.ZERO, new BigDecimal("-1"), new BigDecimal("1.001"), new BigDecimal("100000000.01"))) {
            invalid(() -> expected(money, REFUNDED, PAID, REQUESTED, APPROVED, ZONE));
            invalid(() -> expected(CAPTURED, money, PAID, REQUESTED, APPROVED, ZONE));
        }
        invalid(() -> expected(REFUNDED, CAPTURED, PAID, REQUESTED, APPROVED, ZONE));
        invalid(() -> expected(CAPTURED, REFUNDED, REQUESTED.plusNanos(1), REQUESTED, APPROVED, ZONE));
        invalid(() -> expected(CAPTURED, REFUNDED, PAID, APPROVED.plusNanos(1), APPROVED, ZONE));
        for (Instant time : Arrays.asList(null, Instant.MIN, Instant.MAX)) {
            invalid(() -> expected(CAPTURED, REFUNDED, time, REQUESTED, APPROVED, ZONE));
            invalid(() -> expected(CAPTURED, REFUNDED, PAID, time, APPROVED, ZONE));
            invalid(() -> expected(CAPTURED, REFUNDED, PAID, REQUESTED, time, ZONE));
        }
        for (String zone : Arrays.asList(null, "", " Asia/Kolkata", "Asia/Kolkata ", "Invalid/Synthetic", "UTC+5", "x".repeat(61)))
            invalid(() -> expected(CAPTURED, REFUNDED, PAID, REQUESTED, APPROVED, zone));
        invalid(() -> GatewayRefundVerification.verify(null, observation(refund(RefundStatus.SUCCESS, PROCESSED))));
    }

    @Test
    void originalIdsBindingAndCanonicalProviderReferencesAreRequired() {
        for (int field = 0; field < 5; field++) {
            int target = field;
            invalid(() -> new Expected(target == 0 ? null : REFUND, target == 1 ? null : BINDING,
                    target == 2 ? null : PAYMENT, target == 3 ? null : CAPTURE,
                    target == 4 ? "../synthetic_order" : ORDER, PAYMENT_REF, CAPTURED, REFUNDED, PAID, REQUESTED, APPROVED, ZONE));
        }
        invalid(() -> new Expected(REFUND, BINDING, PAYMENT, CAPTURE, ORDER, "synthetic\npayment", CAPTURED, REFUNDED, PAID, REQUESTED, APPROVED, ZONE));
    }

    @Test
    void invalidServerReadCompletionTimeIsRejectedSafely() {
        for (Instant time : Arrays.asList(null, Instant.MIN, Instant.MAX))
            invalid(() -> new Observation(BINDING, refund(RefundStatus.SUCCESS, PROCESSED), time));
        invalid(() -> GatewayRefundVerification.verify(expected(), new Observation(BINDING, null, APPROVED.minusNanos(1))));
    }

    @Test
    void evidenceHashIgnoresTransportRetryTimeButIncludesExactSourceTime() {
        var source = refund(RefundStatus.SUCCESS, PROCESSED);
        var first = GatewayRefundVerification.verify(expected(), new Observation(BINDING, source, OBSERVED));
        var retry = GatewayRefundVerification.verify(expected(), new Observation(BINDING, source, OBSERVED.plusSeconds(300)));
        var changed = verify(refund(RefundStatus.SUCCESS, PROCESSED.minusNanos(1)));
        assertThat(first).isEqualTo(retry);
        assertThat(changed.evidenceSha256()).isNotEqualTo(first.evidenceSha256());
        var beforeProcessingTime = GatewayRefundVerification.verify(expected(),
                new Observation(BINDING, source, PROCESSED.minusNanos(1)));
        assertThat(beforeProcessingTime.outcome()).isEqualTo(REVIEW_REQUIRED);
        assertThat(beforeProcessingTime.success()).isEmpty();
        // Same facts, genuinely different independent read: never cache the
        // first time-sensitive classification solely under the source hash.
        assertThat(beforeProcessingTime.evidenceSha256()).isEqualTo(first.evidenceSha256());
        assertThat(first.outcome()).isEqualTo(VERIFIED_SUCCESS);
        assertThat(GatewayRefundVerification.verify(expected(), new Observation(BINDING, null, OBSERVED)).evidenceSha256())
                .isEqualTo(GatewayRefundVerification.verify(expected(), new Observation(BINDING, null, OBSERVED.plusSeconds(10))).evidenceSha256());
    }

    @Test
    void everyOriginalProvenanceTermContributesToTheEvidenceHash() {
        var initial = expected();
        var baseline = GatewayRefundVerification.verify(initial, observation(refund(RefundStatus.SUCCESS, PROCESSED))).evidenceSha256();
        var variants = List.of(
                new Expected(uuid(11), BINDING, PAYMENT, CAPTURE, ORDER, PAYMENT_REF, CAPTURED, REFUNDED, PAID, REQUESTED, APPROVED, ZONE),
                new Expected(REFUND, BINDING, uuid(12), CAPTURE, ORDER, PAYMENT_REF, CAPTURED, REFUNDED, PAID, REQUESTED, APPROVED, ZONE),
                new Expected(REFUND, BINDING, PAYMENT, uuid(13), ORDER, PAYMENT_REF, CAPTURED, REFUNDED, PAID, REQUESTED, APPROVED, ZONE),
                new Expected(REFUND, BINDING, PAYMENT, CAPTURE, "synthetic_other", PAYMENT_REF, CAPTURED, REFUNDED, PAID, REQUESTED, APPROVED, ZONE),
                new Expected(REFUND, BINDING, PAYMENT, CAPTURE, ORDER, "synthetic_other", CAPTURED, REFUNDED, PAID, REQUESTED, APPROVED, ZONE),
                expected(new BigDecimal("1000.01"), REFUNDED, PAID, REQUESTED, APPROVED, ZONE),
                expected(CAPTURED, new BigDecimal("29.30"), PAID, REQUESTED, APPROVED, ZONE),
                expected(CAPTURED, REFUNDED, PAID.minusNanos(1), REQUESTED, APPROVED, ZONE),
                expected(CAPTURED, REFUNDED, PAID, REQUESTED.minusNanos(1), APPROVED, ZONE),
                expected(CAPTURED, REFUNDED, PAID, REQUESTED, APPROVED.minusNanos(1), ZONE),
                expected(CAPTURED, REFUNDED, PAID, REQUESTED, APPROVED, "UTC"),
                new Expected(REFUND, binding(ORGANISATION, SCHOOL, PROVIDER, "CASHFREE", "TEST", "synthetic_merchant", "env:APP_SYNTHETIC_OTHER"),
                        PAYMENT, CAPTURE, ORDER, PAYMENT_REF, CAPTURED, REFUNDED, PAID, REQUESTED, APPROVED, ZONE));
        for (var variant : variants) assertThat(GatewayRefundVerification.verify(variant, observation(refund(RefundStatus.SUCCESS, PROCESSED))).evidenceSha256())
                .isNotEqualTo(baseline);
    }

    @Test
    void nullEmptyStatusAndMalformedTypedStringsRemainDistinctSourceFacts() {
        var values = Arrays.asList(null, "", "a:b", "a\nb", "?", "\uD800", "\uD801");
        var hashes = new ArrayList<String>();
        for (String value : values) {
            var source = new RefundObservation(value, ORDER, PAYMENT_REF, REFUNDED, "INR", RefundStatus.PENDING, null);
            var result = verify(source);
            assertThat(result.outcome()).isEqualTo(REVIEW_REQUIRED);
            hashes.add(result.evidenceSha256());
        }
        assertThat(hashes).doesNotHaveDuplicates();
        assertThat(verify(refund(RefundStatus.PENDING, null)).evidenceSha256())
                .isNotEqualTo(verify(refund(RefundStatus.FAILED, null)).evidenceSha256());
    }

    @Test
    void verificationDoesNotMutateInputsAndAllNewSensitiveRecordsHaveRedactedToString() {
        var expected = expected();
        var source = refund(RefundStatus.SUCCESS, PROCESSED);
        var observation = observation(source);
        var result = GatewayRefundVerification.verify(expected, observation);
        assertThat(expected.refundAmount()).isEqualTo(REFUNDED);
        assertThat(source.amount()).isEqualTo(REFUNDED);
        assertThat(source.processedAt()).isEqualTo(PROCESSED);
        assertThat(observation.refund()).isSameAs(source);
        for (Object value : List.of(expected, observation, result, result.success().orElseThrow())) {
            assertThat(value.toString()).doesNotContain(REFUND.toString(), PAYMENT.toString(), CAPTURE.toString(), ORDER, PAYMENT_REF,
                    "29.29", "1000.00", "synthetic_merchant", "APP_SYNTHETIC_REFUND", PROCESSED.toString());
        }
    }

    @Test
    void proofProjectionAndResultPresenceCannotContradictTheirOutcome() {
        invalid(() -> new GatewayRefundVerification.VerifiedSuccess(PROCESSED.toString(), PROCESSED, LocalDate.of(2026, 10, 17)));
        invalid(() -> new GatewayRefundVerification.VerifiedSuccess("2026-10-17T18:29:59.999999999+00:00", GatewayExactInstant.floor(PROCESSED), LocalDate.of(2026, 10, 17)));
        invalid(() -> new GatewayRefundVerification.Result(VERIFIED_SUCCESS, Optional.empty(), "a".repeat(64)));
        var proof = verify(refund(RefundStatus.SUCCESS, PROCESSED)).success();
        invalid(() -> new GatewayRefundVerification.Result(NOT_SUCCESSFUL, proof, "a".repeat(64)));
        invalid(() -> new GatewayRefundVerification.Result(UNKNOWN, Optional.empty(), "not-a-hash"));
    }

    private static Expected expected() { return expected(CAPTURED, REFUNDED, PAID, REQUESTED, APPROVED, ZONE); }
    private static Expected expected(BigDecimal capture, BigDecimal refund, Instant paid, Instant requested, Instant approved, String zone) {
        return new Expected(REFUND, BINDING, PAYMENT, CAPTURE, ORDER, PAYMENT_REF, capture, refund, paid, requested, approved, zone);
    }
    private static RefundObservation refund(RefundStatus status, Instant processed) {
        return new RefundObservation(REFUND.toString(), ORDER, PAYMENT_REF, REFUNDED, "INR", status, processed);
    }
    private static Observation observation(RefundObservation refund) { return new Observation(BINDING, refund, OBSERVED); }
    private static GatewayRefundVerification.Result verify(RefundObservation refund) { return GatewayRefundVerification.verify(expected(), observation(refund)); }
    private static void review(Observation observation) {
        var result = GatewayRefundVerification.verify(expected(), observation);
        assertThat(result.outcome()).isEqualTo(REVIEW_REQUIRED);
        assertThat(result.success()).isEmpty();
    }
    private static void invalid(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining(ORDER).hasMessageNotContaining(PAYMENT_REF).hasMessageNotContaining("29.29");
    }
    private static UUID uuid(int suffix) { return UUID.fromString("00000000-0000-4000-8000-" + String.format("%012d", suffix)); }
    private static GatewayWorkQueue.ProviderBinding binding(UUID organisation, UUID school, UUID provider,
            String code, String environment, String merchant, String credentials) {
        return new GatewayWorkQueue.ProviderBinding(organisation, school, provider, code, environment, merchant, credentials);
    }
}
