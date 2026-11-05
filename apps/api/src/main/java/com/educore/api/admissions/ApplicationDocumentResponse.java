package com.educore.api.admissions;

import java.time.Instant;
import java.util.UUID;

public record ApplicationDocumentResponse(
        UUID id,
        String documentCode,
        String displayName,
        boolean required,
        DocumentReviewStatus status,
        DocumentReceivedMethod receivedMethod,
        Instant receivedAt,
        Instant verifiedAt,
        String verifiedByName,
        String reviewNote,
        long version) {
}
