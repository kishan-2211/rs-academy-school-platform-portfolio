package com.educore.api.schoolconfig;

import static com.educore.api.schoolconfig.SchoolConfigurationController.CampusRequest;
import static com.educore.api.schoolconfig.SchoolConfigurationController.SchoolProfileRequest;
import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SchoolConfigurationRequestTest {
    @Test
    void acceptsAuthorityConfirmedSchoolAndCampusConfiguration() {
        UUID schoolId = UUID.randomUUID();
        var profile = new SchoolProfileRequest(
                schoolId, "Synthetic Academy", "Synthetic Academy", "Asia/Kolkata", "INR",
                "CBSE", "Nursery to Class X", "Synthetic motto", List.of("hi-IN", "en-IN"),
                "school@example.test", "+91 9999999999", "Synthetic Principal",
                "Synthetic school address", "Synthetic locality", "Varanasi", "Uttar Pradesh",
                "IN", 700, 800, 20, 10, 0L, true,
                "Synthetic school authority verification");
        var campus = new CampusRequest(
                schoolId, "SYN_01", "Synthetic Campus", "Asia/Kolkata", "ACTIVE", null,
                "Synthetic campus configuration");

        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(profile)).isEmpty();
            assertThat(factory.getValidator().validate(campus)).isEmpty();
        }
    }

    @Test
    void rejectsUnscopedUnconfirmedAndMalformedConfiguration() {
        var profile = new SchoolProfileRequest(
                null, "", "", "", "RUPEE", "", "", null,
                List.of("english"), "not-an-email", "12", null, "", null, "", "", "IND",
                -1, -1, -1, -1, -1L, false, "");
        var campus = new CampusRequest(
                null, "x", "", "", "ARCHIVED", -1L, "");

        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(profile))
                    .extracting(value -> value.getPropertyPath().toString())
                    .contains("schoolId", "legalName", "displayName", "timeZone", "currencyCode",
                            "boardCode", "gradeRange", "supportedLanguages[0].<list element>",
                            "publicEmail", "publicPhone", "addressLine", "city", "stateName",
                            "countryCode", "learnerCountMin", "learnerCountMax",
                            "teachingStaffEstimate", "operationsStaffEstimate", "expectedVersion",
                            "accuracyConfirmed", "reason");
            assertThat(factory.getValidator().validate(campus))
                    .extracting(value -> value.getPropertyPath().toString())
                    .contains("schoolId", "code", "displayName", "timeZone", "status",
                            "expectedVersion", "reason");
        }
    }
}
