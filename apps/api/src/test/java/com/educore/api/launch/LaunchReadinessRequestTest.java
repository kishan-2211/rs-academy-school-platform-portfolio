package com.educore.api.launch;

import static org.assertj.core.api.Assertions.assertThat;

import com.educore.api.launch.LaunchReadinessController.CreateItemRequest;
import com.educore.api.launch.LaunchReadinessController.ReviewDecisionRequest;
import com.educore.api.launch.LaunchReadinessController.ReviseItemRequest;
import com.educore.api.launch.LaunchReadinessController.SubmitReviewRequest;
import jakarta.validation.Validation;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LaunchReadinessRequestTest {
    @Test
    void acceptsConfigurableReadinessAndIndependentReviewRequests() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            UUID schoolId = UUID.randomUUID();
            var create = new CreateItemRequest(
                    schoolId, "PROVIDER_READINESS", "PROVIDERS", "Provider readiness",
                    "Confirm production provider ownership and failure handling.", false,
                    "IN_PROGRESS", null, "Platform administrator", "Awaiting provider review",
                    "Synthetic launch-control setup");
            var revise = new ReviseItemRequest(
                    schoolId, "PROVIDERS", "Provider readiness",
                    "Confirm production provider ownership and failure handling.", false,
                    "READY", "runbook:synthetic-provider-review", "Platform administrator",
                    "Synthetic evidence independently checked", 1,
                    "Synthetic readiness revision");
            var submit = new SubmitReviewRequest(
                    schoolId, "All required controls and their safe references were reviewed.",
                    true, "Synthetic launch review submission");
            var decision = new ReviewDecisionRequest(
                    schoolId, "APPROVED", "Synthetic independent launch approval", 0);

            assertThat(factory.getValidator().validate(create)).isEmpty();
            assertThat(factory.getValidator().validate(revise)).isEmpty();
            assertThat(factory.getValidator().validate(submit)).isEmpty();
            assertThat(factory.getValidator().validate(decision)).isEmpty();
        }
    }

    @Test
    void rejectsMissingScopeEvidenceAndInvalidDecisionRequests() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var create = new CreateItemRequest(
                    null, "!", "", "", "", true, "", null, null, null, "");
            var revise = new ReviseItemRequest(
                    null, "", "", "", true, "", null, null, null, 0, "");
            var submit = new SubmitReviewRequest(null, "", false, "");
            var decision = new ReviewDecisionRequest(null, "GO", "", -1);

            assertThat(factory.getValidator().validate(create))
                    .extracting(value -> value.getPropertyPath().toString())
                    .contains("schoolId", "controlCode", "category", "title", "guidance",
                            "status", "reason");
            assertThat(factory.getValidator().validate(revise))
                    .extracting(value -> value.getPropertyPath().toString())
                    .contains("schoolId", "category", "title", "guidance", "status",
                            "expectedRevision", "reason");
            assertThat(factory.getValidator().validate(submit))
                    .extracting(value -> value.getPropertyPath().toString())
                    .contains("schoolId", "submissionStatement", "evidenceConfirmed", "reason");
            assertThat(factory.getValidator().validate(decision))
                    .extracting(value -> value.getPropertyPath().toString())
                    .contains("schoolId", "decision", "reason", "expectedVersion");
        }
    }
}
