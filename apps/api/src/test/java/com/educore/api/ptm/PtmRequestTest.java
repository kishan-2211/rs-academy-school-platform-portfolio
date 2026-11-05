package com.educore.api.ptm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.validation.Validation;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PtmRequestTest {
    @Test
    void validatesSchoolScopeRetryKeysAndCorrectionEvidence() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertThat(validator.validate(new FamilyPtmController.BookRequest(null, "synthetic-ptm-key"))).isEmpty();
            assertThat(validator.validate(new FamilyPtmController.BookRequest("x".repeat(501), "short")))
                    .extracting(v -> v.getPropertyPath().toString()).contains("topic", "idempotencyKey");
            assertThat(validator.validate(new FamilyPtmController.RescheduleRequest(null, -1, "x")))
                    .extracting(v -> v.getPropertyPath().toString()).contains("slotId", "expectedVersion", "reason");
            assertThat(validator.validate(new PtmController.EventDecisionRequest(null, -1, "")))
                    .extracting(v -> v.getPropertyPath().toString()).contains("schoolId", "expectedVersion", "reason");
        }
    }

    @Test
    void keepsSlotsInsideSessionAndSchoolLocalDate() {
        var date = LocalDate.of(2030, 4, 10);
        PtmService.validateWindows(request(date, Instant.parse("2030-04-10T04:30:00Z"), 3),
                "Asia/Kolkata", date.minusDays(1), date.plusDays(1));
        assertThatThrownBy(() -> PtmService.validateWindows(
                request(date, Instant.parse("2030-04-10T18:20:00Z"), 3),
                "Asia/Kolkata", date.minusDays(1), date.plusDays(1)))
                .hasMessageContaining("Keep slots on the meeting date");
        assertThatThrownBy(() -> PtmService.validateWindows(
                request(date, Instant.parse("2030-04-10T04:30:00Z"), 3),
                "Asia/Kolkata", date.plusDays(1), date.plusDays(30)))
                .hasMessageContaining("within the session");
    }

    private PtmController.CreateEventRequest request(LocalDate date, Instant start, int count) {
        return new PtmController.CreateEventRequest(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "Synthetic meeting", null, null, null, "Synthetic room", date,
                start.minusSeconds(7200), start.minusSeconds(3600), start.minusSeconds(1800),
                60, start, 10, 5, count, "synthetic-schedule-key");
    }
}
