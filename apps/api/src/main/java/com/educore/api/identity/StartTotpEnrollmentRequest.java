package com.educore.api.identity;

import jakarta.validation.constraints.Size;

public record StartTotpEnrollmentRequest(@Size(max = 80) String label) {
}
