package com.educore.api.documents;

import static org.assertj.core.api.Assertions.assertThat;

import com.educore.api.documents.OfficialDocumentController.DecisionRequest;
import com.educore.api.documents.OfficialDocumentController.IssueRequest;
import com.educore.api.documents.OfficialDocumentController.TemplateRequest;
import com.educore.api.documents.OfficialDocumentController.TransitionRequest;
import jakarta.validation.Validation;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OfficialDocumentRequestTest {

    @Test
    void acceptsScopedTemplateIssueAndMakerCheckerRequests() {
        UUID schoolId = UUID.randomUUID();
        var template = new TemplateRequest(
                schoolId, "REPORT_CARD_EN", 1, null, "REPORT_CARD", "English report card",
                "en-IN", "Annual report card", "Academic session 2026–27",
                "Verify the QR before relying on this record.", "Principal", "#23483F",
                true, Map.of("learner", "Learner"), null, "Synthetic template setup");
        var issue = new IssueRequest(
                schoolId, UUID.randomUUID(), "LEARNER_REPORT_CARD", UUID.randomUUID(),
                null, "Synthetic controlled issue");
        var decision = new DecisionRequest(
                schoolId, "APPROVE", "Synthetic independent approval", 0);
        var transition = new TransitionRequest(
                schoolId, 0, "Synthetic controlled transition");

        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(template)).isEmpty();
            assertThat(factory.getValidator().validate(issue)).isEmpty();
            assertThat(factory.getValidator().validate(decision)).isEmpty();
            assertThat(factory.getValidator().validate(transition)).isEmpty();
        }
    }

    @Test
    void rejectsUnscopedMalformedAndUnevidencedRequests() {
        var template = new TemplateRequest(
                null, "bad-code", 0, null, "", "", "", "", null, "", "", "red",
                true, Map.of("", ""), -1L, "");
        var issue = new IssueRequest(null, null, "", null, null, "");
        var decision = new DecisionRequest(null, "", "", -1);
        var transition = new TransitionRequest(null, -1, "");

        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(template))
                    .extracting(value -> value.getPropertyPath().toString())
                    .contains("schoolId", "templateCode", "revisionNumber", "documentKind",
                            "displayName", "locale", "title", "footerText", "signatoryRole",
                            "accentColour", "labelOverrides<K>[].<map key>",
                            "labelOverrides[].<map value>", "expectedVersion", "reason");
            assertThat(factory.getValidator().validate(issue))
                    .extracting(value -> value.getPropertyPath().toString())
                    .contains("schoolId", "templateId", "sourceType", "sourceId", "reason");
            assertThat(factory.getValidator().validate(decision))
                    .extracting(value -> value.getPropertyPath().toString())
                    .contains("schoolId", "decision", "reason", "expectedVersion");
            assertThat(factory.getValidator().validate(transition))
                    .extracting(value -> value.getPropertyPath().toString())
                    .contains("schoolId", "expectedVersion", "reason");
        }
    }
}
