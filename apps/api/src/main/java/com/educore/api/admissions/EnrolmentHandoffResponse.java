package com.educore.api.admissions;

public record EnrolmentHandoffResponse(
        ApplicationDetailResponse application,
        EnrolmentSummary enrolment) {
}
