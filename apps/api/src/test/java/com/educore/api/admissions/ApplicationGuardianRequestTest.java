package com.educore.api.admissions;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

class ApplicationGuardianRequestTest {

    @Test
    void acceptsAContactWithEitherEmailOrPhone() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var request = new AddApplicationGuardianRequest(
                    new GuardianInput(
                            "Synthetic Guardian",
                            "PARENT_GUARDIAN",
                            null,
                            "+91 90000 00000",
                            false,
                            true),
                    0L);

            assertThat(factory.getValidator().validate(request)).isEmpty();
        }
    }

    @Test
    void validatesNestedGuardianAndOptimisticVersion() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var request = new UpdateApplicationGuardianRequest(
                    new GuardianInput("", "", "not-an-email", null, false, false),
                    -1L);

            assertThat(factory.getValidator().validate(request))
                    .extracting(violation -> violation.getPropertyPath().toString())
                    .contains(
                            "guardian.displayName",
                            "guardian.relationshipCode",
                            "guardian.email",
                            "expectedVersion");
        }
    }
}
