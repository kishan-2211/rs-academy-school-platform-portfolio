package com.educore.api.finance;

import static org.assertj.core.api.Assertions.assertThat;

import com.educore.api.finance.FinanceController.GatewayCapturePostingRequest;
import jakarta.validation.Validation;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GatewayCaptureReviewRequestTest {
    @Test void acceptsScopedOpenDateAndBoundedReviewEvidence() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(new GatewayCapturePostingRequest(
                    UUID.randomUUID(), LocalDate.of(2026, 10, 10), "Synthetic reviewed financial posting"))).isEmpty();
            assertThat(factory.getValidator().validate(new GatewayCapturePostingRequest(
                    UUID.randomUUID(), LocalDate.of(2026, 10, 10), "x".repeat(240)))).isEmpty();
        }
    }

    @Test void rejectsMissingSchoolDateAndBlankReviewEvidence() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(new GatewayCapturePostingRequest(null, null, " ")))
                    .extracting(value -> value.getPropertyPath().toString())
                    .containsExactlyInAnyOrder("schoolId", "openDate", "reason");
            assertThat(factory.getValidator().validate(new GatewayCapturePostingRequest(
                    UUID.randomUUID(), LocalDate.of(2026, 10, 10), null)))
                    .extracting(value -> value.getPropertyPath().toString()).containsExactly("reason");
        }
    }

    @Test void rejectsOversizedReviewEvidenceWithoutAcceptingPaymentOverrides() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(new GatewayCapturePostingRequest(
                    UUID.randomUUID(), LocalDate.of(2026, 10, 10), "x".repeat(241))))
                    .extracting(value -> value.getPropertyPath().toString()).containsExactly("reason");
        }
        assertThat(GatewayCapturePostingRequest.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactly("schoolId", "openDate", "reason");
    }
}
