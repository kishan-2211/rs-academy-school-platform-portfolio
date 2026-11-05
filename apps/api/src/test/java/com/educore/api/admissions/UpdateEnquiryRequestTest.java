package com.educore.api.admissions;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class UpdateEnquiryRequestTest {

    @Test
    void acceptsACompleteFollowUpUpdate() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var request = new UpdateEnquiryRequest(
                    EnquiryStatus.OPEN,
                    null,
                    Instant.parse("2026-08-21T05:30:00Z"),
                    true,
                    "Spoke with the guardian and scheduled a school visit.",
                    3L);

            assertThat(factory.getValidator().validate(request)).isEmpty();
        }
    }

    @Test
    void rejectsMissingWorkflowStateAndOversizedPrivateNotes() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var request = new UpdateEnquiryRequest(
                    null,
                    null,
                    null,
                    false,
                    "x".repeat(1001),
                    null);

            assertThat(factory.getValidator().validate(request))
                    .extracting(violation -> violation.getPropertyPath().toString())
                    .containsExactlyInAnyOrder("status", "note", "expectedVersion");
        }
    }
}
