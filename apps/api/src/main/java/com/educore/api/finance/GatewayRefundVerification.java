package com.educore.api.finance;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Pure classification of an independently read refund against approved, persisted
 * school intent. This is not provider admission, a lease, an endpoint or financial
 * posting authority. Unknown and unsuccessful reads never release refund budget.
 */
final class GatewayRefundVerification {
    private GatewayRefundVerification() {}

    record Expected(UUID refundId, GatewayWorkQueue.ProviderBinding binding,
            UUID paymentId, UUID captureId, String orderReference, String paymentReference,
            BigDecimal captureAmount, BigDecimal refundAmount, Instant originalPaidAt,
            Instant requestedAt, Instant approvedAt, String schoolTimeZone) {
        Expected {
            try {
                Objects.requireNonNull(refundId);
                Objects.requireNonNull(binding);
                Objects.requireNonNull(paymentId);
                Objects.requireNonNull(captureId);
                PaymentProviderAdapter.requireReference(orderReference);
                PaymentProviderAdapter.requireReference(paymentReference);
                captureAmount = exactMoney(captureAmount);
                refundAmount = exactMoney(refundAmount);
                if (refundAmount.compareTo(captureAmount) > 0) throw invalidExpected();
                GatewayExactInstant.canonical(originalPaidAt);
                GatewayExactInstant.canonical(requestedAt);
                GatewayExactInstant.canonical(approvedAt);
                if (originalPaidAt.isAfter(requestedAt) || requestedAt.isAfter(approvedAt)
                        || schoolTimeZone == null || schoolTimeZone.length() > 60
                        || !ZoneId.of(schoolTimeZone).getId().equals(schoolTimeZone)) {
                    throw invalidExpected();
                }
            } catch (RuntimeException invalid) {
                // Server configuration/identity validation must not leak its input.
                throw invalidExpected();
            }
        }

        @Override public String toString() { return "Expected[private approved refund provenance]"; }
    }

    record Observation(GatewayWorkQueue.ProviderBinding binding,
            PaymentProviderAdapter.RefundObservation refund, Instant observedAt) {
        Observation {
            try { GatewayExactInstant.canonical(observedAt); }
            catch (RuntimeException invalid) { throw invalidObservation(); }
        }

        @Override public String toString() { return "Observation[private independent refund facts]"; }
    }

    enum Outcome { UNKNOWN, NOT_SUCCESSFUL, REVIEW_REQUIRED, VERIFIED_SUCCESS }

    record VerifiedSuccess(String processedAtExact, Instant processedAtProjection, LocalDate processedOn) {
        VerifiedSuccess {
            try {
                Instant exact = GatewayExactInstant.parseCanonical(processedAtExact);
                if (!GatewayExactInstant.floor(exact).equals(processedAtProjection)
                        || processedOn == null || processedOn.getYear() < 1 || processedOn.getYear() > 9999) {
                    throw invalidSuccess();
                }
            } catch (RuntimeException invalid) { throw invalidSuccess(); }
        }

        @Override public String toString() { return "VerifiedSuccess[private exact refund processing evidence]"; }
    }

    record Result(Outcome outcome, Optional<VerifiedSuccess> success, String evidenceSha256) {
        Result {
            if (outcome == null || success == null || (outcome == Outcome.VERIFIED_SUCCESS) != success.isPresent()
                    || evidenceSha256 == null || !evidenceSha256.matches("[a-f0-9]{64}")) {
                throw new IllegalArgumentException("Consistent internal refund verification result required");
            }
        }

        @Override public String toString() { return "Result[private refund verification outcome]"; }
    }

    static Result verify(Expected expected, Observation observation) {
        if (expected == null) throw invalidExpected();
        String hash = evidenceHash(expected, observation);
        if (observation == null) return result(Outcome.UNKNOWN, hash);
        if (!expected.binding().equals(observation.binding())) return result(Outcome.REVIEW_REQUIRED, hash);
        // The server-authored read completion time cannot precede its approved work.
        if (observation.observedAt().isBefore(expected.approvedAt())) throw invalidObservation();
        var refund = observation.refund();
        if (refund == null) return result(Outcome.UNKNOWN, hash);
        if (refund.processedAt() != null) {
            try { GatewayExactInstant.canonical(refund.processedAt()); }
            catch (IllegalArgumentException malformed) { return result(Outcome.REVIEW_REQUIRED, hash); }
            if (refund.processedAt().isBefore(expected.approvedAt())
                    || refund.processedAt().isBefore(expected.originalPaidAt())
                    || refund.processedAt().isAfter(observation.observedAt())) {
                return result(Outcome.REVIEW_REQUIRED, hash);
            }
        }
        var decision = GatewayPaymentEvidence.refund(new PaymentProviderAdapter.RefundRequest(
                expected.refundId(), expected.orderReference(), expected.paymentReference(), expected.refundAmount()),
                refund, observation.observedAt());
        if (decision == GatewayPaymentEvidence.RefundDecision.REVIEW_REQUIRED) return result(Outcome.REVIEW_REQUIRED, hash);
        if (decision == GatewayPaymentEvidence.RefundDecision.NOT_SUCCESSFUL) return result(Outcome.NOT_SUCCESSFUL, hash);
        LocalDate processedOn = refund.processedAt().atZone(ZoneId.of(expected.schoolTimeZone())).toLocalDate();
        if (processedOn.getYear() < 1 || processedOn.getYear() > 9999) return result(Outcome.REVIEW_REQUIRED, hash);
        return new Result(Outcome.VERIFIED_SUCCESS, Optional.of(new VerifiedSuccess(
                GatewayExactInstant.canonical(refund.processedAt()), GatewayExactInstant.floor(refund.processedAt()), processedOn)), hash);
    }

    private static Result result(Outcome outcome, String hash) { return new Result(outcome, Optional.empty(), hash); }

    private static BigDecimal exactMoney(BigDecimal value) {
        PaymentProviderAdapter.requireAmount(value);
        return value.setScale(2, RoundingMode.UNNECESSARY);
    }

    /**
     * Versioned fixed-order length-prefixed fields. Encode Java UTF-16 code units
     * explicitly (big endian, no BOM), including malformed typed strings without
     * replacement-character collisions. NULL is -1:, distinct from empty text.
     * observedAt is transport/read time, deliberately not part of source identity.
     */
    private static String evidenceHash(Expected expected, Observation observation) {
        var hash = new EvidenceHash();
        hash.field("educore.gateway-refund-get-evidence:v1");
        hash.field(expected.refundId().toString());
        binding(hash, expected.binding());
        hash.field(expected.paymentId().toString());
        hash.field(expected.captureId().toString());
        hash.field(expected.orderReference());
        hash.field(expected.paymentReference());
        hash.field(expected.captureAmount().toPlainString());
        hash.field(expected.refundAmount().toPlainString());
        hash.field(GatewayExactInstant.canonical(expected.originalPaidAt()));
        hash.field(GatewayExactInstant.canonical(expected.requestedAt()));
        hash.field(GatewayExactInstant.canonical(expected.approvedAt()));
        hash.field(expected.schoolTimeZone());
        hash.field(observation == null ? "NO_OBSERVATION" : "OBSERVATION");
        binding(hash, observation == null ? null : observation.binding());
        var refund = observation == null ? null : observation.refund();
        hash.field(refund == null ? "NO_REFUND_FACTS" : "REFUND_FACTS");
        hash.field(refund == null ? null : refund.refundReference());
        hash.field(refund == null ? null : refund.orderReference());
        hash.field(refund == null ? null : refund.paymentReference());
        hash.field(refund == null ? null : moneyFact(refund.amount()));
        hash.field(refund == null ? null : refund.currencyCode());
        hash.field(refund == null || refund.status() == null ? null : refund.status().name());
        hash.field(refund == null || refund.processedAt() == null ? null : refund.processedAt().toString());
        return hash.finish();
    }

    private static void binding(EvidenceHash hash, GatewayWorkQueue.ProviderBinding binding) {
        hash.field(binding == null ? null : binding.organisationId().toString());
        hash.field(binding == null ? null : binding.schoolId().toString());
        hash.field(binding == null ? null : binding.providerConfigId().toString());
        hash.field(binding == null ? null : binding.providerCode());
        hash.field(binding == null ? null : binding.environment());
        hash.field(binding == null ? null : binding.merchantReference());
        hash.field(binding == null ? null : binding.credentialsReference());
    }

    private static String moneyFact(BigDecimal amount) {
        if (amount == null) return null;
        try { return "EXACT:" + exactMoney(amount).toPlainString(); }
        catch (IllegalArgumentException invalid) {
            // Scientific form preserves invalid precision without expanding a
            // malicious negative scale into an enormous plain decimal string.
            return "INVALID:" + amount.toString();
        }
    }

    private static final class EvidenceHash {
        private final MessageDigest digest;
        EvidenceHash() {
            try { digest = MessageDigest.getInstance("SHA-256"); }
            catch (NoSuchAlgorithmException unavailable) { throw new IllegalStateException("SHA-256 is unavailable"); }
        }
        void field(String value) {
            digest.update((value == null ? "-1:" : value.length() + ":").getBytes(StandardCharsets.US_ASCII));
            if (value != null) for (int index = 0; index < value.length(); index++) {
                char unit = value.charAt(index);
                digest.update((byte) (unit >>> 8));
                digest.update((byte) unit);
            }
        }
        String finish() { return HexFormat.of().formatHex(digest.digest()); }
        @Override public String toString() { return "EvidenceHash[private refund source digest]"; }
    }

    private static IllegalArgumentException invalidExpected() {
        return new IllegalArgumentException("Canonical approved scoped refund provenance required");
    }
    private static IllegalArgumentException invalidObservation() {
        return new IllegalArgumentException("Supported honest refund read-completion instant required");
    }
    private static IllegalArgumentException invalidSuccess() {
        return new IllegalArgumentException("Exact refund processing source and matching projection required");
    }
}
