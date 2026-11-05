package com.educore.api.admissions;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

class CreateEnquiryRequestTest {

    @Test
    void acceptsACompleteEnquiry() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var request = new CreateEnquiryRequest(
                    "A Parent",
                    "parent@example.com",
                    "+91 98765 43210",
                    "admissions",
                    "I would like to understand the admissions process.",
                    EnquiryIntent.APPLICATION,
                    true);

            assertThat(factory.getValidator().validate(request)).isEmpty();
        }
    }

    @Test
    void rejectsMissingConsentAndUnsafePhoneCharacters() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var request = new CreateEnquiryRequest(
                    "A Parent",
                    "parent@example.com",
                    "+91 <script>",
                    "admissions",
                    "I would like to understand the admissions process.",
                    EnquiryIntent.GENERAL,
                    false);

            assertThat(factory.getValidator().validate(request))
                    .extracting(violation -> violation.getPropertyPath().toString())
                    .containsExactlyInAnyOrder("phone", "privacyAccepted");
        }
    }
}
