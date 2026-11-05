package com.educore.api.studentportal;

import static org.assertj.core.api.Assertions.assertThat;

import com.educore.api.identity.AuthenticatedUser;
import jakarta.validation.Validation;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

class StudentPortalRequestTest {

    @Test
    void acceptsPreparedConsentGuardianDecisionsAndOwnSubmissionRequests() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            var prepare = new PrepareStudentPortalAccessRequest(
                    UUID.randomUUID(), "student.2026-001", "child-account-v1",
                    "Provide age-appropriate access to the learner's own school records.",
                    "SCHOOL_RECORD", "school-record:synthetic-review-001", 2, 3,
                    "Synthetic staff preparation after adult verification");
            var approve = new StudentPortalDecisionRequest(
                    "APPROVE", "Synthetic-learner-passphrase", 0,
                    "Synthetic guardian approval after reviewing the notice");
            var decline = new StudentPortalDecisionRequest(
                    "DECLINE", null, 0, "Synthetic guardian declined access");
            var transition = new StudentPortalVersionedReason(
                    1, "Synthetic accountable lifecycle transition");
            var submission = new StudentPortalController.SubmissionRequest(
                    "SUBMIT", "Synthetic learner response", null, 4);

            assertThat(validator.validate(prepare)).isEmpty();
            assertThat(validator.validate(approve)).isEmpty();
            assertThat(validator.validate(decline)).isEmpty();
            assertThat(validator.validate(transition)).isEmpty();
            assertThat(validator.validate(submission)).isEmpty();
        }
    }

    @Test
    void rejectsUnsafeConsentDecisionsAndSubmissionBounds() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            var prepare = new PrepareStudentPortalAccessRequest(
                    null, "!", "", "too short", "RAW_IDENTITY_DOCUMENT", "",
                    -1, -1, "");
            var approvalWithoutPassword = new StudentPortalDecisionRequest(
                    "APPROVE", null, -1, "");
            var declineWithPassword = new StudentPortalDecisionRequest(
                    "DECLINE", "password-must-not-be-collected", 0, "Synthetic decline");
            var transition = new StudentPortalVersionedReason(-1, "");
            var submission = new StudentPortalController.SubmissionRequest(
                    "DELETE", "x".repeat(8001), "x".repeat(1001), -1);

            assertThat(validator.validate(prepare))
                    .extracting(value -> value.getPropertyPath().toString())
                    .contains("guardianId", "loginIdentifier", "privacyNoticeVersion", "purposeSummary",
                            "adultVerificationMethod", "adultVerificationReference",
                            "expectedLearnerVersion", "expectedRelationshipVersion", "reason");
            assertThat(validator.validate(approvalWithoutPassword))
                    .extracting(value -> value.getPropertyPath().toString())
                    .contains("passwordValidForDecision", "expectedVersion", "reason");
            assertThat(validator.validate(declineWithPassword))
                    .extracting(value -> value.getPropertyPath().toString())
                    .contains("passwordValidForDecision");
            assertThat(validator.validate(transition))
                    .extracting(value -> value.getPropertyPath().toString())
                    .contains("expectedVersion", "reason");
            assertThat(validator.validate(submission))
                    .extracting(value -> value.getPropertyPath().toString())
                    .contains("action", "responseText", "responseLink", "expectedVersion");
        }
    }

    @Test
    void studentAndLifecycleEndpointsRemainCapabilityGated() throws Exception {
        var studentClassGuard = StudentPortalController.class.getAnnotation(PreAuthorize.class);
        var staffSnapshot = StudentPortalAccessController.class.getDeclaredMethod(
                "snapshot", AuthenticatedUser.class, UUID.class).getAnnotation(PreAuthorize.class);
        var guardianDecision = StudentPortalAccessController.class.getDeclaredMethod(
                "decide", AuthenticatedUser.class, UUID.class, UUID.class,
                StudentPortalDecisionRequest.class).getAnnotation(PreAuthorize.class);
        var attendance = StudentPortalController.class.getDeclaredMethod(
                "attendance", AuthenticatedUser.class, LocalDate.class, LocalDate.class);
        var calendar = StudentPortalController.class.getDeclaredMethod(
                "calendar", AuthenticatedUser.class);

        assertThat(studentClassGuard.value()).isEqualTo("hasAuthority('CAPABILITY_portal.student')");
        assertThat(staffSnapshot.value()).isEqualTo("hasAuthority('CAPABILITY_student.portal.manage')");
        assertThat(guardianDecision.value()).isEqualTo("hasAuthority('CAPABILITY_portal.guardian')");
        assertThat(attendance).isNotNull();
        assertThat(calendar).isNotNull();
    }
}
