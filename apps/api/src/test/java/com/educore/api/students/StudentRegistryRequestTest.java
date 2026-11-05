package com.educore.api.students;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StudentRegistryRequestTest {

    @Test
    void acceptsAuditableProfileAndPlacementCorrections() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var profile = new UpdateStudentProfileRequest(
                    "Synthetic Learner", null, LocalDate.now().minusYears(10),
                    "NOT_STATED", null, 0L, "Verified against the synthetic source record");
            var placement = new PlaceStudentRequest(
                    UUID.randomUUID(), "12", LocalDate.now(), 0L, "Initial section allocation");

            assertThat(factory.getValidator().validate(profile)).isEmpty();
            assertThat(factory.getValidator().validate(placement)).isEmpty();
        }
    }

    @Test
    void rejectsUnversionedOrUnauditedSensitiveChanges() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var profile = new UpdateStudentProfileRequest(
                    "", null, LocalDate.now().plusDays(1), "", null, -1L, "");
            var guardian = new UpdateGuardianRequest(
                    "", "not-an-email", null, "", false, false, -1L, -1L, "");

            assertThat(factory.getValidator().validate(profile))
                    .extracting(violation -> violation.getPropertyPath().toString())
                    .contains("legalName", "birthDate", "genderCode", "expectedVersion", "correctionReason");
            assertThat(factory.getValidator().validate(guardian))
                    .extracting(violation -> violation.getPropertyPath().toString())
                    .contains(
                            "displayName", "email", "relationshipCode", "expectedGuardianVersion",
                            "expectedRelationshipVersion", "correctionReason");
        }
    }
}
