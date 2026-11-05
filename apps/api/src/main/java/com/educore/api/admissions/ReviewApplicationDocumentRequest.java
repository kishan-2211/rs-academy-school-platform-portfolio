package com.educore.api.admissions;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record ReviewApplicationDocumentRequest(
        @NotNull DocumentReviewStatus status,
        DocumentReceivedMethod receivedMethod,
        @Size(max = 500) String reviewNote,
        @NotNull @PositiveOrZero Long expectedVersion) {
}
