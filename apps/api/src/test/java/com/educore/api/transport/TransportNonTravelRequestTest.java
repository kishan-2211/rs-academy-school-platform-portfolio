package com.educore.api.transport;

import static org.assertj.core.api.Assertions.*;
import static com.educore.api.transport.TransportNonTravelModels.*;
import com.educore.api.identity.AuthenticatedUser;
import jakarta.validation.Validation;
import java.time.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

class TransportNonTravelRequestTest {
    @Test
    void validatesScopeRetryIdentityAndBoundedPolicyEvidence() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertThat(validator.validate(new PolicyRequest(UUID.randomUUID(), UUID.randomUUID(), true,
                    LocalTime.of(7, 0), LocalTime.of(12, 0), null, "Synthetic school policy"))).isEmpty();
            assertThat(validator.validate(new ChangeRequest(UUID.randomUUID(), LocalDate.now(), "FULL_DAY", true,
                    UUID.randomUUID(), UUID.randomUUID(), null, null))).isEmpty();
            assertThat(validator.validate(new PolicyRequest(null, null, null, null, null, -1L, "")))
                    .extracting(v -> v.getPropertyPath().toString()).contains("schoolId", "campusId", "enabled", "expectedVersion", "reason");
            assertThat(validator.validate(new ChangeRequest(null, null, "OTHER", null, null, null, null, null)))
                    .extracting(v -> v.getPropertyPath().toString()).contains("clientRequestId", "serviceDate", "journeys", "notTravelling");
        }
    }

    @Test
    void resolvesSchoolLocalTimeAndFailsClosedForAmbiguousOrMissingTime() {
        assertThat(TransportNonTravelService.localDeadline(LocalDate.of(2026, 10, 3), LocalTime.of(7, 0), "Asia/Kolkata"))
                .isEqualTo(Instant.parse("2026-10-03T01:30:00Z"));
        assertThatThrownBy(() -> TransportNonTravelService.localDeadline(LocalDate.of(2026, 3, 8),
                LocalTime.of(2, 30), "America/New_York")).hasMessageContaining("ambiguous or unavailable");
        assertThatThrownBy(() -> TransportNonTravelService.localDeadline(LocalDate.of(2026, 11, 1),
                LocalTime.of(1, 30), "America/New_York")).hasMessageContaining("ambiguous or unavailable");
    }

    @Test
    void enforcesServerSideCapabilitiesForEveryEndpoint() throws Exception {
        for (String method : new String[]{"day", "change"}) {
            var signature = "day".equals(method) ? new Class<?>[]{AuthenticatedUser.class, UUID.class}
                    : new Class<?>[]{AuthenticatedUser.class, UUID.class, ChangeRequest.class};
            assertThat(FamilyNonTravelController.class.getDeclaredMethod(method, signature)
                    .getAnnotation(PreAuthorize.class).value()).isEqualTo("hasAuthority('CAPABILITY_portal.guardian')");
        }
        assertThat(TransportNonTravelController.class.getDeclaredMethod("policies", AuthenticatedUser.class, UUID.class)
                .getAnnotation(PreAuthorize.class).value()).isEqualTo("hasAuthority('CAPABILITY_transport.configure')");
        assertThat(TransportNonTravelController.class.getDeclaredMethod("policy", AuthenticatedUser.class, PolicyRequest.class)
                .getAnnotation(PreAuthorize.class).value()).isEqualTo("hasAuthority('CAPABILITY_transport.configure')");
    }
}
