package com.educore.api.learning;

import static org.assertj.core.api.Assertions.assertThat;

import com.educore.api.family.FamilyController.AssignmentSubmissionRequest;
import com.educore.api.learning.LearningController.*;
import jakarta.validation.Validation;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LearningRequestTest {

    @Test
    void categoryCodesMatchTheExistingDatabaseFormatBeforeInsertion() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            for (String code : java.util.List.of("A", "1HOMEWORK", "SYN-HOMEWORK", "HAS SPACE", "BAD!", "A".repeat(31))) {
                var request = new CategoryRequest(UUID.randomUUID(), UUID.randomUUID(), code,
                        "Synthetic category", "#5B9279", 0, "ACTIVE", null, null);
                assertThat(factory.getValidator().validate(request))
                        .extracting(violation -> violation.getPropertyPath().toString()).contains("code");
            }
            for (String code : java.util.List.of("HW", "synthetic_homework", "A".repeat(30))) {
                var request = new CategoryRequest(UUID.randomUUID(), UUID.randomUUID(), code,
                        "Synthetic category", "#5B9279", 0, "ACTIVE", null, null);
                assertThat(factory.getValidator().validate(request)).isEmpty();
            }
        }
    }

    @Test
    void acceptsConfigurableAssignmentAndFamilySubmissionRequests() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var category = new CategoryRequest(
                    UUID.randomUUID(), UUID.randomUUID(), "PROJECT", "Project",
                    "#5B9279", 1, "ACTIVE", null, "Synthetic policy setup");
            var assignment = new AssignmentRequest(
                    UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                    "Synthetic science project", "Explain the synthetic observation.",
                    "Reference", "https://example.test/resource",
                    Instant.parse("2026-08-21T03:30:00Z"),
                    Instant.parse("2026-08-25T10:30:00Z"), "TEXT_OR_LINK",
                    new BigDecimal("20"), true, 3, false,
                    true, Instant.parse("2026-08-27T10:30:00Z"),
                    null, null);
            var familySubmission = new AssignmentSubmissionRequest(
                    "SUBMIT", "Synthetic learner response", null, 0);

            assertThat(factory.getValidator().validate(category)).isEmpty();
            assertThat(factory.getValidator().validate(assignment)).isEmpty();
            assertThat(factory.getValidator().validate(familySubmission)).isEmpty();
        }
    }

    @Test
    void rejectsUnsafeAssignmentBounds() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var category = new CategoryRequest(
                    null, null, "", "", "red", 1001, "", -1L, null);
            var assignment = new AssignmentRequest(
                    null, null, null, null, "", "", null, "x".repeat(1001),
                    null, null, "", new BigDecimal("10001"), true, 11, true,
                    false, null, -1L, "x".repeat(241));
            var review = new ReviewRequest(
                    null, "", "x".repeat(3001), new BigDecimal("10001"), -1, "");

            assertThat(factory.getValidator().validate(category))
                    .extracting(violation -> violation.getPropertyPath().toString())
                    .contains("schoolId", "academicSessionId", "code", "name", "accentColor",
                            "displayOrder", "status", "expectedVersion");
            assertThat(factory.getValidator().validate(assignment))
                    .extracting(violation -> violation.getPropertyPath().toString())
                    .contains("schoolId", "academicTermId", "offeringId", "title", "instructions",
                            "resourceUrl", "availableFrom", "dueAt", "submissionMode",
                            "maximumPoints", "maxAttachmentCount", "expectedVersion", "reason");
            assertThat(factory.getValidator().validate(review))
                    .extracting(violation -> violation.getPropertyPath().toString())
                    .contains("schoolId", "decision", "feedback", "pointsAwarded",
                            "expectedVersion", "reason");
        }
    }
}
