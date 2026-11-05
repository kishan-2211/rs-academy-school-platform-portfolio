package com.educore.api.admissions;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record AdmissionDecisionResponse(
        UUID id,
        AdmissionDecision decision,
        String reasonCode,
        String internalSummary,
        LocalDate offerExpiresOn,
        String decidedByName,
        UUID supersedesDecisionId,
        Instant createdAt) {
}
