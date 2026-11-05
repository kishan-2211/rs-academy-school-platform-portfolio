package com.educore.api.admissions;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class RteAdmissionRequestTest {
    @Test
    void legacyRequestsDefaultToRegularAndDoNotClearExistingClassificationOnUpdate() {
        var create = new CreateApplicationRequest(null, null, null, null, "Synthetic intake", "I",
                "Synthetic learner", null, LocalDate.of(2018, 4, 1), ApplicantGender.NOT_STATED, null, null);
        var update = new UpdateApplicationRequest(null, null, "Synthetic intake", "I", "Synthetic learner",
                null, create.birthDate(), ApplicantGender.NOT_STATED, null, null, 0L);
        assertThat(create.admissionType()).isEqualTo(AdmissionType.REGULAR);
        assertThat(create.rteReference()).isNull();
        assertThat(AdmissionType.orRegular(null)).isEqualTo(AdmissionType.REGULAR);
        assertThat(update.admissionType()).isNull();
        assertThat(update.rteReference()).isNull();
    }

    @Test
    void optionalReferenceIsBoundedOnBothRequests() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            var create = new CreateApplicationRequest(null, null, null, null, "Synthetic intake", "I",
                    "Synthetic learner", null, LocalDate.of(2018, 4, 1), ApplicantGender.NOT_STATED, null, null,
                    AdmissionType.RTE, "a".repeat(101));
            var update = new UpdateApplicationRequest(null, null, "Synthetic intake", "I", "Synthetic learner",
                    null, create.birthDate(), ApplicantGender.NOT_STATED, null, null, 0L, AdmissionType.RTE, "a".repeat(101));
            assertThat(validator.validate(create)).extracting(v -> v.getPropertyPath().toString()).containsExactly("rteReference");
            assertThat(validator.validate(update)).extracting(v -> v.getPropertyPath().toString()).containsExactly("rteReference");
            assertThat(validator.validate(new CreateApplicationRequest(null, null, null, null, "Synthetic intake", "I",
                    "Synthetic learner", null, create.birthDate(), ApplicantGender.NOT_STATED, null, null, AdmissionType.RTE, null))).isEmpty();
        }
    }
}
