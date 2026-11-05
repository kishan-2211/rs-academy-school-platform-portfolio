package com.educore.api.finance;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.HashSet;

/** Minimal family DTOs; provider routing, private contact and raw work evidence never escape. */
public final class FamilyGatewayPaymentModels {
    private FamilyGatewayPaymentModels() {}

    public enum Availability { AVAILABLE, DISABLED, UNAVAILABLE }
    public enum Status { PAID, REVIEW_REQUIRED, READY, PENDING, EXPIRED, RELEASED }

    public record ChargeSelection(@NotNull UUID chargeId,
            @NotNull @DecimalMin("0.01") @DecimalMax("100000000.00") @Digits(integer = 9, fraction = 2) BigDecimal amount) {}

    public record InitiateRequest(@NotNull UUID clientRequestId,
            @NotNull @Size(min = 1, max = 100) List<@NotNull @Valid ChargeSelection> allocations,
            @NotNull @Pattern(regexp = "[0-9]{10}") String payerPhone, UUID accountId) {
        public InitiateRequest(UUID clientRequestId, List<ChargeSelection> allocations, String payerPhone) {
            this(clientRequestId, allocations, payerPhone, null);
        }
        public InitiateRequest { allocations = allocations == null ? null : List.copyOf(allocations); }
        @AssertTrue(message = "Selected fee charges must be unique.")
        public boolean isUniqueCharges() {
            if (allocations == null) return true; // The required-field constraint reports this separately.
            var identities = new HashSet<UUID>();
            for (var allocation : allocations) {
                if (allocation != null && allocation.chargeId() != null && !identities.add(allocation.chargeId())) return false;
            }
            return true;
        }
        @Override public String toString() { return "InitiateRequest[private family fee payment terms]"; }
    }

    public record SelectedCharge(UUID chargeId, String label, LocalDate dueOn, BigDecimal amount) {}
    public record Receipt(UUID paymentId, String receiptNumber, LocalDate postingOn) {}

    public record Payment(UUID id, UUID learnerId, UUID accountId, String sessionName,
            BigDecimal amount, String currencyCode, Status status, Instant createdAt, Instant expiresAt,
            LocalDate originalBusinessDate, boolean reservationActive, boolean initiatedByYou,
            boolean canContinue, List<SelectedCharge> selectedCharges, Receipt receipt) {
        public Payment { selectedCharges = List.copyOf(selectedCharges); }
    }

    public record History(UUID learnerId, Availability availability, int page, int pageSize,
            boolean hasMore, List<Payment> payments) {
        public History { payments = List.copyOf(payments); }
    }

    /** Sensitive, single-action response only; never included in payment history. */
    public record Continuation(UUID attemptId, URI checkoutUrl, Instant expiresAt) {
        @Override public String toString() { return "Continuation[private authenticated checkout redirect]"; }
    }
}
