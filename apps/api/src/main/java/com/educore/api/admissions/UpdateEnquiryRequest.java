package com.educore.api.admissions;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

public record UpdateEnquiryRequest(
        @NotNull EnquiryStatus status,
        UUID assignedMembershipId,
        Instant followUpAt,
        boolean recordContact,
        @Size(max = 1000) String note,
        @NotNull @PositiveOrZero Long expectedVersion) {
}
