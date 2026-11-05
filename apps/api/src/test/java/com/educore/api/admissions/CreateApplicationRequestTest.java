package com.educore.api.admissions;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class CreateApplicationRequestTest {

    @Test
    void acceptsAValidMinimalApplication() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var request = new CreateApplicationRequest(
                    null,
                    null,
                    null,
                    null,
                    "Next intake",
                    "VI",
                    "Synthetic Applicant",
                    null,
                    LocalDate.now().minusYears(10),
                    ApplicantGender.NOT_STATED,
                    null,
                    List.of(new GuardianInput(
                            "Synthetic Guardian",
                            "PARENT_GUARDIAN",
                            "guardian@example.test",
                            null,
                            true,
                            false)));

            assertThat(factory.getValidator().validate(request)).isEmpty();
        }
    }

    @Test
    void rejectsFutureBirthDatesAndMalformedGuardianContacts() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var request = new CreateApplicationRequest(
                    null,
                    null,
                    null,
                    null,
                    " ",
                    "",
                    "",
                    null,
                    LocalDate.now().plusDays(1),
                    ApplicantGender.NOT_STATED,
                    null,
                    List.of(new GuardianInput(
                            "",
                            "",
                            "not-an-email",
                            null,
                            true,
                            false)));

            assertThat(factory.getValidator().validate(request))
                    .extracting(violation -> violation.getPropertyPath().toString())
                    .contains(
                            "intakeSessionLabel",
                            "targetGradeCode",
                            "applicantLegalName",
                            "birthDate",
                            "guardians[0].displayName",
                            "guardians[0].relationshipCode",
                            "guardians[0].email");
        }
    }
}
