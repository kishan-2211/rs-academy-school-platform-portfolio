package com.educore.api.studentportal;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record PrepareStudentPortalAccessRequest(
        @NotNull UUID guardianId,
        @NotBlank @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._-]{2,79}") String loginIdentifier,
        @NotBlank @Size(max = 60) String privacyNoticeVersion,
        @NotBlank @Size(min = 20, max = 1000) String purposeSummary,
        @NotBlank @Pattern(regexp = "SCHOOL_RECORD|AUTHORIZED_TOKEN|OTHER_REVIEWED")
                String adultVerificationMethod,
        @NotBlank @Size(min = 3, max = 160) String adultVerificationReference,
        @Min(0) long expectedLearnerVersion,
        @Min(0) long expectedRelationshipVersion,
        @NotBlank @Size(min = 3, max = 500) String reason) {}
