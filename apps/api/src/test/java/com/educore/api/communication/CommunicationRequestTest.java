package com.educore.api.communication;

import static org.assertj.core.api.Assertions.assertThat;

import com.educore.api.communication.CommunicationController.*;
import jakarta.validation.Validation;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CommunicationRequestTest {
    @Test
    void acceptsBilingualTemplateAnnouncementAndMakerCheckerRequests() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var template = new TemplateRequest(UUID.randomUUID(), "FEE_REMINDER", 1, null,
                    "Fee reminder", "FEES", "BILINGUAL", "Fee reminder", "शुल्क स्मरण",
                    "Please review the verified fee statement.", "कृपया सत्यापित शुल्क विवरण देखें।",
                    true, true, true, false, false, null, "Synthetic template setup");
            var announcement = new AnnouncementRequest(UUID.randomUUID(), null, "GENERAL", "IMPORTANT",
                    "SECTION_FAMILIES", UUID.randomUUID(), "BILINGUAL", "School notice", "विद्यालय सूचना",
                    "Synthetic English notice.", "परीक्षण हिन्दी सूचना।", true, true, false, false, false,
                    Instant.parse("2026-09-01T04:00:00Z"), Instant.parse("2026-09-08T04:00:00Z"),
                    null, "Synthetic draft");
            var transition = new VersionedReason(UUID.randomUUID(), 0, "Synthetic authority review");
            var decision = new DecisionRequest(UUID.randomUUID(), "APPROVED", 1,
                    "Synthetic maker-checker approval");

            assertThat(factory.getValidator().validate(template)).isEmpty();
            assertThat(factory.getValidator().validate(announcement)).isEmpty();
            assertThat(factory.getValidator().validate(transition)).isEmpty();
            assertThat(factory.getValidator().validate(decision)).isEmpty();
        }
    }

    @Test
    void rejectsMissingScopeUnsafeIdentifiersAndBlankEvidence() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var template = new TemplateRequest(null, "!", 0, null, "", "", "", null, null,
                    null, null, false, false, false, false, false, -1L, null);
            var announcement = new AnnouncementRequest(null, null, "", "", "", null, "",
                    null, null, null, null, false, false, false, false, false,
                    null, null, -1L, null);
            var transition = new VersionedReason(null, -1, "");
            var decision = new DecisionRequest(null, "", -1, "");

            assertThat(factory.getValidator().validate(template))
                    .extracting(value -> value.getPropertyPath().toString())
                    .contains("schoolId", "code", "revisionNumber", "name", "category", "languageMode",
                            "expectedVersion");
            assertThat(factory.getValidator().validate(announcement))
                    .extracting(value -> value.getPropertyPath().toString())
                    .contains("schoolId", "category", "priority", "audienceKind", "languageMode",
                            "scheduledFor", "expectedVersion");
            assertThat(factory.getValidator().validate(transition))
                    .extracting(value -> value.getPropertyPath().toString())
                    .contains("schoolId", "expectedVersion", "reason");
            assertThat(factory.getValidator().validate(decision))
                    .extracting(value -> value.getPropertyPath().toString())
                    .contains("schoolId", "decision", "expectedVersion", "reason");
        }
    }
}
