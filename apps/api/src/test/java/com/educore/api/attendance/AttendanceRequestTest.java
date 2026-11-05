package com.educore.api.attendance;

import static org.assertj.core.api.Assertions.assertThat;

import com.educore.api.attendance.AttendanceController.CalendarDayRequest;
import com.educore.api.attendance.AttendanceController.MarkInput;
import com.educore.api.attendance.AttendanceController.PolicyRequest;
import com.educore.api.attendance.AttendanceController.RecordMarksRequest;
import jakarta.validation.Validation;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AttendanceRequestTest {

    @Test
    void draftReviewsRequireScopedVersionedInputAndAnExplicitDecision() {
        try(var factory=Validation.buildDefaultValidatorFactory()) {
            var valid=new AttendanceController.DraftLeaveReviewRequest(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),
                    UUID.randomUUID(),1,0,0,"KEEP_RECORDED","Synthetic observed attendance");
            assertThat(factory.getValidator().validate(valid)).isEmpty();
            var invalid=new AttendanceController.DraftLeaveReviewRequest(null,null,null,null,0,-1,-1,"AUTO_PRESENT"," ");
            assertThat(factory.getValidator().validate(invalid)).extracting(v->v.getPropertyPath().toString())
                    .contains("schoolId","operationId","markId","leaveRequestId","leaveRequestVersion",
                            "expectedRegisterVersion","expectedMarkVersion","decision","reason");
        }
    }

    @Test
    void leaveCorrectionSourceIsOptionalButMustBeCompleteAndVersioned() {
        try(var factory=Validation.buildDefaultValidatorFactory()) {
            var validator=factory.getValidator();
            var manual=new AttendanceController.CorrectionRequest(UUID.randomUUID(),UUID.randomUUID(),"PRESENT","Synthetic observation");
            assertThat(validator.validate(manual)).isEmpty();
            var invalid=new AttendanceController.CorrectionRequest(manual.schoolId(),manual.markId(),"EXCUSED","Synthetic source",
                    new AttendanceController.LeaveCorrectionSource(null,0L,-1L));
            assertThat(validator.validate(invalid)).extracting(v->v.getPropertyPath().toString())
                    .contains("leaveSource.requestId","leaveSource.requestVersion","leaveSource.expectedMarkVersion");
            var incomplete=new AttendanceController.CorrectionRequest(manual.schoolId(),manual.markId(),"EXCUSED","Synthetic source",
                    new AttendanceController.LeaveCorrectionSource(UUID.randomUUID(),null,null));
            assertThat(validator.validate(incomplete)).hasSize(2);
            var valid=new AttendanceController.CorrectionRequest(manual.schoolId(),manual.markId(),"EXCUSED","Synthetic source",
                    new AttendanceController.LeaveCorrectionSource(UUID.randomUUID(),1L,0L));
            assertThat(validator.validate(valid)).isEmpty();
        }
    }

    @Test
    void acceptsAuthorityConfiguredPolicyAndDatedRegisterChanges() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var policy = new PolicyRequest(
                    UUID.randomUUID(), UUID.randomUUID(), "Synthetic attendance policy", "BOTH",
                    List.of("PRESENT", "ABSENT", "LATE"), LocalTime.of(9, 30),
                    Map.of(
                            "PRESENT", BigDecimal.ONE,
                            "ABSENT", BigDecimal.ZERO,
                            "LATE", new BigDecimal("0.75")),
                    24, 7, new BigDecimal("75.00"), "ACTIVE", null, null);
            var calendarDay = new CalendarDayRequest(
                    UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                    LocalDate.of(2026, 8, 21), "INSTRUCTIONAL", "Synthetic school day",
                    true, "ACTIVE", null, null);
            var marks = new RecordMarksRequest(
                    UUID.randomUUID(), 0,
                    List.of(new MarkInput(UUID.randomUUID(), "PRESENT", null, 0L)),
                    "Synthetic morning roll call");

            assertThat(factory.getValidator().validate(policy)).isEmpty();
            assertThat(factory.getValidator().validate(calendarDay)).isEmpty();
            assertThat(factory.getValidator().validate(marks)).isEmpty();
        }
    }

    @Test
    void rejectsUnsafePolicyBoundsAndEmptyMarkBatches() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var policy = new PolicyRequest(
                    UUID.randomUUID(), UUID.randomUUID(), "", "",
                    List.of(), null, Map.of(), 721, 91, new BigDecimal("101"), "", -1L, null);
            var marks = new RecordMarksRequest(UUID.randomUUID(), -1, List.of(), null);

            assertThat(factory.getValidator().validate(policy))
                    .extracting(violation -> violation.getPropertyPath().toString())
                    .contains("name", "registerMode", "enabledStates", "stateWeights", "lockAfterHours",
                            "guardianExplanationDays", "warningThresholdPercent", "status", "expectedVersion");
            assertThat(factory.getValidator().validate(marks))
                    .extracting(violation -> violation.getPropertyPath().toString())
                    .contains("expectedRegisterVersion", "marks");
        }
    }
}
