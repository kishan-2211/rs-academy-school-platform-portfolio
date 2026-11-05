package com.educore.api.studentportal;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record StudentPortalVersionedReason(
        @Min(0) long expectedVersion,
        @NotBlank @Size(min = 3, max = 500) String reason) {}
