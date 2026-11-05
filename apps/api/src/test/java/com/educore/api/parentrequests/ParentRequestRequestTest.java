package com.educore.api.parentrequests;

import static org.assertj.core.api.Assertions.assertThat;

import com.educore.api.parentrequests.FamilyParentRequestController.OpenRequest;
import com.educore.api.parentrequests.FamilyParentRequestController.ReplyRequest;
import com.educore.api.parentrequests.ParentRequestController.AssignmentRequest;
import com.educore.api.parentrequests.ParentRequestController.StaffReplyRequest;
import com.educore.api.parentrequests.ParentRequestController.TransitionRequest;
import jakarta.validation.Validation;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ParentRequestRequestTest {
    @Test
    void acceptsScopedRetrySafeWorkflowRequests() {
        UUID schoolId = UUID.randomUUID();
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertThat(validator.validate(new OpenRequest("ATTENDANCE", "Absence record",
                    "Please review the attendance record.", "synthetic-open-key"))).isEmpty();
            assertThat(validator.validate(new ReplyRequest("Additional context", "synthetic-reply-key", 1))).isEmpty();
            assertThat(validator.validate(new AssignmentRequest(schoolId, UUID.randomUUID(),
                    "HIGH", 2, "Assigned to class teacher"))).isEmpty();
            assertThat(validator.validate(new StaffReplyRequest(schoolId, "Reviewed with the class record.",
                    false, "synthetic-staff-key", 3))).isEmpty();
            assertThat(validator.validate(new TransitionRequest(schoolId, "RESOLVE", 4,
                    "Attendance record confirmed with the family"))).isEmpty();
        }
    }

    @Test
    void rejectsMissingScopeBlankEvidenceAndInvalidVersions() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertThat(validator.validate(new OpenRequest("", "x", "", "short")))
                    .extracting(value -> value.getPropertyPath().toString())
                    .contains("category", "subject", "message", "idempotencyKey");
            assertThat(validator.validate(new ReplyRequest("", "short", -1)))
                    .extracting(value -> value.getPropertyPath().toString())
                    .contains("message", "idempotencyKey", "expectedVersion");
            assertThat(validator.validate(new AssignmentRequest(null, null, "", -1, "x")))
                    .extracting(value -> value.getPropertyPath().toString())
                    .contains("schoolId", "staffProfileId", "priority", "expectedVersion", "reason");
            assertThat(validator.validate(new StaffReplyRequest(null, "", false, "short", -1)))
                    .extracting(value -> value.getPropertyPath().toString())
                    .contains("schoolId", "message", "idempotencyKey", "expectedVersion");
            assertThat(validator.validate(new TransitionRequest(null, "", -1, "x")))
                    .extracting(value -> value.getPropertyPath().toString())
                    .contains("schoolId", "action", "expectedVersion", "reason");
        }
    }
}
