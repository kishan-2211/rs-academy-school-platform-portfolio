package com.educore.api.schoolcalendar;

import static org.assertj.core.api.Assertions.assertThat;

import com.educore.api.schoolcalendar.SchoolCalendarController.CreateNoticeRequest;
import com.educore.api.schoolcalendar.SchoolCalendarController.CreatePolicyRequest;
import com.educore.api.schoolcalendar.SchoolCalendarController.ReviewDecisionRequest;
import com.educore.api.schoolcalendar.SchoolCalendarController.ReviseNoticeRequest;
import com.educore.api.schoolcalendar.SchoolCalendarController.RevisePolicyRequest;
import com.educore.api.schoolcalendar.SchoolCalendarController.SubmitReviewRequest;
import jakarta.validation.Validation;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SchoolCalendarRequestTest {
    @Test
    void acceptsAuthorityConfirmedBilingualCalendarRequests() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            UUID schoolId = UUID.randomUUID();
            var policy = new CreatePolicyRequest(
                    schoolId, UUID.randomUUID(), UUID.randomUUID(), LocalTime.of(8, 0),
                    60, LocalTime.of(18, 0), "Asia/Kolkata", true, true, true,
                    true, "Synthetic authority-confirmed session policy");
            var revisedPolicy = new RevisePolicyRequest(
                    schoolId, LocalTime.of(8, 15), 45, LocalTime.of(18, 30),
                    "Asia/Kolkata", true, true, true, 1, true,
                    "Synthetic authority-confirmed policy revision");
            var notice = new CreateNoticeRequest(
                    schoolId, UUID.randomUUID(), UUID.randomUUID(), LocalDate.of(2026, 9, 1),
                    "CLOSURE", "GOVERNMENT_ORDER", "School closed",
                    "विद्यालय बंद रहेगा", "The school will remain closed.",
                    "विद्यालय बंद रहेगा।", "Synthetic government order reference",
                    true, "Synthetic bilingual closure draft");
            var revision = new ReviseNoticeRequest(
                    schoolId, "INSTRUCTIONAL", "REOPENING", "School reopens",
                    "विद्यालय पुनः खुलेगा", "Classes will resume.",
                    "कक्षाएँ पुनः आरंभ होंगी।", "Synthetic reopening reference",
                    1, true, "Synthetic reopening correction");
            var submit = new SubmitReviewRequest(
                    schoolId, 1, true, "Synthetic evidence-confirmed review submission");
            var decision = new ReviewDecisionRequest(
                    schoolId, "APPROVED", 0, "Synthetic independent calendar approval");

            assertThat(factory.getValidator().validate(policy)).isEmpty();
            assertThat(factory.getValidator().validate(revisedPolicy)).isEmpty();
            assertThat(factory.getValidator().validate(notice)).isEmpty();
            assertThat(factory.getValidator().validate(revision)).isEmpty();
            assertThat(factory.getValidator().validate(submit)).isEmpty();
            assertThat(factory.getValidator().validate(decision)).isEmpty();
        }
    }

    @Test
    void rejectsMissingScopeInvalidStatesAndUnconfirmedEvidence() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var policy = new CreatePolicyRequest(
                    null, null, null, null, 241, null, "", false, false, false,
                    false, "");
            var revisedPolicy = new RevisePolicyRequest(
                    null, null, -1, null, "", false, false, false, 0, false, "");
            var notice = new CreateNoticeRequest(
                    null, null, null, null, "OPEN", "", "", "", "", "",
                    null, false, "");
            var revision = new ReviseNoticeRequest(
                    null, "OPEN", "", "", "", "", "", null, 0, false, "");
            var submit = new SubmitReviewRequest(null, 0, false, "");
            var decision = new ReviewDecisionRequest(null, "PUBLISH", -1, "");

            assertThat(factory.getValidator().validate(policy))
                    .extracting(value -> value.getPropertyPath().toString())
                    .contains("schoolId", "campusId", "academicSessionId", "schoolStartTime",
                            "sameDayCutoffMinutes", "reminderTime", "timeZone",
                            "authorityConfirmed", "reason");
            assertThat(factory.getValidator().validate(revisedPolicy))
                    .extracting(value -> value.getPropertyPath().toString())
                    .contains("schoolId", "schoolStartTime", "sameDayCutoffMinutes",
                            "reminderTime", "timeZone", "expectedRevision",
                            "authorityConfirmed", "reason");
            assertThat(factory.getValidator().validate(notice))
                    .extracting(value -> value.getPropertyPath().toString())
                    .contains("schoolId", "campusId", "academicSessionId", "calendarDate",
                            "dayType", "noticeKind", "titleEn", "titleHi", "bodyEn", "bodyHi",
                            "bilingualContentConfirmed", "reason");
            assertThat(factory.getValidator().validate(revision))
                    .extracting(value -> value.getPropertyPath().toString())
                    .contains("schoolId", "dayType", "noticeKind", "titleEn", "titleHi",
                            "bodyEn", "bodyHi", "expectedRevision",
                            "bilingualContentConfirmed", "reason");
            assertThat(factory.getValidator().validate(submit))
                    .extracting(value -> value.getPropertyPath().toString())
                    .contains("schoolId", "expectedRevision", "evidenceConfirmed", "reason");
            assertThat(factory.getValidator().validate(decision))
                    .extracting(value -> value.getPropertyPath().toString())
                    .contains("schoolId", "decision", "expectedVersion", "reason");
        }
    }
}
