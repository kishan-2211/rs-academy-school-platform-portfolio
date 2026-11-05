package com.educore.api.workforce;

import static com.educore.api.workforce.WorkforceController.*;
import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WorkforceRequestTest {
    @Test
    void acceptsConfiguredAttendanceAndRetrySafeLeaveWorkflows() {
        UUID school = UUID.randomUUID();
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var policy = new PolicyRequest(school, UUID.randomUUID(), UUID.randomUUID(),
                    "Synthetic workforce attendance", List.of("PRESENT", "ABSENT", "LEAVE", "HALF_DAY"),
                    Map.of("PRESENT", BigDecimal.ONE, "ABSENT", BigDecimal.ZERO,
                            "LEAVE", BigDecimal.ONE, "HALF_DAY", new BigDecimal("0.50")),
                    24, new BigDecimal("75.00"), 3, "ACTIVE", null, null);
            var type = new LeaveTypeRequest(school, "CASUAL_LEAVE", "Casual leave",
                    true, true, new BigDecimal("5.00"), "ACTIVE", null, null);
            var balance = new LeaveBalanceRequest(school, UUID.randomUUID(), UUID.randomUUID(),
                    UUID.randomUUID(), "OPENING", new BigDecimal("12.00"),
                    "synthetic-balance-0001", "Synthetic approved opening balance");
            var marks = new RecordMarksRequest(school, 0,
                    List.of(new MarkInput(UUID.randomUUID(), "PRESENT", LocalTime.of(8, 0),
                            LocalTime.of(15, 0), null, 0L)), "Synthetic daily staff roll call");
            var leave = new LeaveRequest(school, UUID.randomUUID(), UUID.randomUUID(),
                    LocalDate.of(2026, 8, 28), LocalDate.of(2026, 8, 28), "FULL_DAY",
                    "synthetic-leave-0001", "Synthetic personal leave request");

            assertThat(factory.getValidator().validate(policy)).isEmpty();
            assertThat(factory.getValidator().validate(type)).isEmpty();
            assertThat(factory.getValidator().validate(balance)).isEmpty();
            assertThat(factory.getValidator().validate(marks)).isEmpty();
            assertThat(factory.getValidator().validate(leave)).isEmpty();
        }
    }

    @Test
    void rejectsMissingScopeUnsafeBoundsAndUnusableRetryKeys() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var policy = new PolicyRequest(null, null, null, "", List.of(), Map.of(),
                    721, new BigDecimal("101"), 31, "", -1L, null);
            var balance = new LeaveBalanceRequest(null, null, null, null, "", null, "short", "");
            var marks = new RecordMarksRequest(null, -1, List.of(), "");
            var leave = new LeaveRequest(null, null, null, null, null, "", "short", "");

            assertThat(factory.getValidator().validate(policy))
                    .extracting(value -> value.getPropertyPath().toString())
                    .contains("schoolId", "sessionId", "campusId", "name", "enabledStates",
                            "stateWeights", "lockAfterHours", "warningThresholdPercent",
                            "consecutiveAbsenceThreshold", "status", "expectedVersion");
            assertThat(factory.getValidator().validate(balance))
                    .extracting(value -> value.getPropertyPath().toString())
                    .contains("schoolId", "sessionId", "staffProfileId", "leaveTypeId",
                            "entryKind", "quantity", "reason");
            assertThat(factory.getValidator().validate(marks))
                    .extracting(value -> value.getPropertyPath().toString())
                    .contains("schoolId", "expectedRegisterVersion", "marks", "reason");
            assertThat(factory.getValidator().validate(leave))
                    .extracting(value -> value.getPropertyPath().toString())
                    .contains("schoolId", "sessionId", "leaveTypeId", "startsOn", "endsOn",
                            "duration", "reason");
        }
    }
}
