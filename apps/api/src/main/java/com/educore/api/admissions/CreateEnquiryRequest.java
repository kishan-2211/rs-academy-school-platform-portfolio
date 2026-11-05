package com.educore.api.admissions;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateEnquiryRequest(
        @NotBlank @Size(min = 2, max = 100) String name,
        @NotBlank @Email @Size(max = 254) String email,
        @Size(max = 30) @Pattern(regexp = "^[+0-9() .-]*$", message = "must be a valid phone number") String phone,
        @Size(max = 60) String topic,
        @NotBlank @Size(min = 10, max = 2000) String message,
        EnquiryIntent intent,
        @AssertTrue(message = "must be accepted") boolean privacyAccepted) {
}
