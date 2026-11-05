package com.educore.api.academics;

import static org.assertj.core.api.Assertions.assertThat;

import com.educore.api.academics.AcademicRolloverController.RolloverExecutionRequest;
import com.educore.api.academics.AcademicRolloverController.RolloverPreviewRequest;
import com.educore.api.academics.AcademicRolloverController.StudentRolloverDecisionRequest;
import jakarta.validation.Validation;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AcademicRolloverRequestTest {

    @Test
    void acceptsCompletePreviewBoundExecution() {
        UUID school = UUID.randomUUID();
        UUID session = UUID.randomUUID();
        var preview = new RolloverPreviewRequest(
                school,
                session,
                "2027-2028",
                LocalDate.of(2027, 4, 1),
                LocalDate.of(2028, 3, 31),
                true,
                true,
                true,
                true,
                List.of(new StudentRolloverDecisionRequest(
                        UUID.randomUUID(), "PROMOTE", "VII", "A", "12", null,
                        "Promoted after completing the session")));
        var execution = new RolloverExecutionRequest(
                preview,
                "rollover-2027-2028-0001",
                "a".repeat(64),
                "Approved annual rollover after final records review");

        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(preview)).isEmpty();
            assertThat(factory.getValidator().validate(execution)).isEmpty();
        }
    }

    @Test
    void rejectsMissingScopeEvidenceAndStudentDecisionDetails() {
        var invalidPreview = new RolloverPreviewRequest(
                null,
                null,
                " ",
                null,
                null,
                false,
                false,
                false,
                false,
                List.of(new StudentRolloverDecisionRequest(
                        null, " ", "X".repeat(41), "A".repeat(31),
                        "1".repeat(31), "A".repeat(21), " ")));
        var invalidExecution = new RolloverExecutionRequest(
                invalidPreview, "short", "not-a-hash", " ");

        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(invalidPreview))
                    .extracting(violation -> violation.getPropertyPath().toString())
                    .contains(
                            "schoolId",
                            "sourceSessionId",
                            "targetSessionName",
                            "targetStartsOn",
                            "targetEndsOn",
                            "studentDecisions[0].sourceEnrolmentId",
                            "studentDecisions[0].outcome",
                            "studentDecisions[0].targetGradeCode",
                            "studentDecisions[0].targetSectionCode",
                            "studentDecisions[0].targetRollNumber",
                            "studentDecisions[0].leaveStatus",
                            "studentDecisions[0].reason");
            assertThat(factory.getValidator().validate(invalidExecution))
                    .extracting(violation -> violation.getPropertyPath().toString())
                    .contains("idempotencyKey", "snapshotHash", "reason", "preview.schoolId");
        }
    }
}
