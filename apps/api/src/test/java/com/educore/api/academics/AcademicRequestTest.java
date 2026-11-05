package com.educore.api.academics;

import static org.assertj.core.api.Assertions.assertThat;

import com.educore.api.academics.AcademicController.DraftMutationRequest;
import com.educore.api.academics.AcademicController.CurriculumDraftRequest;
import com.educore.api.academics.AcademicController.CurriculumMutationRequest;
import com.educore.api.academics.AcademicController.PeriodRequest;
import com.educore.api.academics.AcademicController.ResourceRequest;
import jakarta.validation.Validation;
import java.time.LocalTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AcademicRequestTest {

    @Test
    void acceptsAuthorityManagedResourcesAndAuditedDraftMutations() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var resource = new ResourceRequest(
                    UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "VI", "TEXTBOOK",
                    "Synthetic mathematics text", null, null, "Synthetic publisher", null,
                    null, "https://example.test/book", 1, "ACTIVE", null, null);
            var mutation = new DraftMutationRequest(
                    UUID.randomUUID(), 2L, "Synthetic publication review completed");

            assertThat(factory.getValidator().validate(resource)).isEmpty();
            assertThat(factory.getValidator().validate(mutation)).isEmpty();
        }
    }

    @Test
    void rejectsOutOfRangePeriodsAndUnauditedPublication() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var period = new PeriodRequest(
                    UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 8, 0, "", "",
                    LocalTime.of(10, 0), LocalTime.of(9, 0), "", "", -1L, null);
            var mutation = new DraftMutationRequest(UUID.randomUUID(), -1L, "");

            assertThat(factory.getValidator().validate(period))
                    .extracting(violation -> violation.getPropertyPath().toString())
                    .contains("dayOfWeek", "sequenceNumber", "code", "name", "kind", "status", "expectedVersion");
            assertThat(factory.getValidator().validate(mutation))
                    .extracting(violation -> violation.getPropertyPath().toString())
                    .contains("expectedPlanVersion", "reason");
        }
    }

    @Test
    void requiresAuditedCurriculumDraftAndPublicationMutations() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var draft = new CurriculumDraftRequest(
                    UUID.randomUUID(), UUID.randomUUID(), "VI", "Synthetic catalogue review");
            var publish = new CurriculumMutationRequest(
                    UUID.randomUUID(), 0, "Synthetic authority approval");
            var invalidDraft = new CurriculumDraftRequest(
                    UUID.randomUUID(), UUID.randomUUID(), "", "");
            var invalidPublish = new CurriculumMutationRequest(UUID.randomUUID(), -1, "");

            assertThat(factory.getValidator().validate(draft)).isEmpty();
            assertThat(factory.getValidator().validate(publish)).isEmpty();
            assertThat(factory.getValidator().validate(invalidDraft))
                    .extracting(violation -> violation.getPropertyPath().toString())
                    .contains("gradeCode", "reason");
            assertThat(factory.getValidator().validate(invalidPublish))
                    .extracting(violation -> violation.getPropertyPath().toString())
                    .contains("expectedVersion", "reason");
        }
    }
}
