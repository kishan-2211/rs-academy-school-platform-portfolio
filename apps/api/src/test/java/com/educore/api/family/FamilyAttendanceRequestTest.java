package com.educore.api.family;

import static org.assertj.core.api.Assertions.assertThat;

import com.educore.api.family.FamilyController.AttendanceExplanationRequest;
import com.educore.api.identity.AuthenticatedUser;
import jakarta.validation.Validation;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

class FamilyAttendanceRequestTest {

    @Test
    void validatesGuardianAttendanceExplanationLength() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(
                    new AttendanceExplanationRequest("Medical appointment; supporting note is available.")))
                    .isEmpty();
            assertThat(factory.getValidator().validate(new AttendanceExplanationRequest(" ")))
                    .extracting(violation -> violation.getPropertyPath().toString())
                    .contains("explanation");
        }
    }

    @Test
    void familyCalendarEndpointRemainsGuardianCapabilityGated() throws Exception {
        var guard = FamilyController.class.getDeclaredMethod(
                "calendar", AuthenticatedUser.class, UUID.class).getAnnotation(PreAuthorize.class);

        assertThat(guard.value()).isEqualTo("hasAuthority('CAPABILITY_portal.guardian')");
    }
}
