package com.educore.api.finance;

import static com.educore.api.finance.FeeAccountingController.*;
import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FeeAccountingRequestTest {
    @Test
    void acceptsCompleteConfigurationMakerCheckerAndPostingRequests() {
        UUID schoolId = UUID.randomUUID();
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var configuration = new ConfigurationRequest(schoolId, UUID.randomUUID(),
                    null, 1, LocalDate.of(2026, 4, 1), UUID.randomUUID(),
                    UUID.randomUUID(),
                    List.of(new HeadMappingRequest(UUID.randomUUID(), UUID.randomUUID())),
                    List.of(
                            new TenderMappingRequest("CASH", UUID.randomUUID()),
                            new TenderMappingRequest("BANK_TRANSFER", UUID.randomUUID()),
                            new TenderMappingRequest("CHEQUE", UUID.randomUUID()),
                            new TenderMappingRequest("SCHOOL_UPI", UUID.randomUUID()),
                            new TenderMappingRequest("ONLINE_PROVIDER", UUID.randomUUID())),
                    null, "Synthetic complete mapping");
            var submit = new VersionedReasonRequest(schoolId, 0,
                    "Synthetic maker submission");
            var decision = new ConfigurationDecisionRequest(schoolId, "APPROVE", 1,
                    "Synthetic independent approval");
            var posting = new PostingRequest(schoolId, UUID.randomUUID(),
                    LocalDate.of(2026, 8, 28), "PAYMENT", 200,
                    "Synthetic ledger preparation");

            assertThat(factory.getValidator().validate(configuration)).isEmpty();
            assertThat(factory.getValidator().validate(submit)).isEmpty();
            assertThat(factory.getValidator().validate(decision)).isEmpty();
            assertThat(factory.getValidator().validate(posting)).isEmpty();
        }
    }

    @Test
    void rejectsMissingScopeMappingsVersionsAndBatchBounds() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var configuration = new ConfigurationRequest(null, null, null, 0, null,
                    null, null, List.of(), List.of(), -1L, "");
            var submit = new VersionedReasonRequest(null, -1, "");
            var decision = new ConfigurationDecisionRequest(null, "", -1, "");
            var posting = new PostingRequest(null, null, null, "", 201, "");

            assertThat(factory.getValidator().validate(configuration))
                    .extracting(value -> value.getPropertyPath().toString())
                    .contains("schoolId", "financialYearId", "revisionNumber",
                            "effectiveFrom", "headMappings", "tenderMappings",
                            "expectedVersion", "reason");
            assertThat(factory.getValidator().validate(submit))
                    .extracting(value -> value.getPropertyPath().toString())
                    .contains("schoolId", "expectedVersion", "reason");
            assertThat(factory.getValidator().validate(decision))
                    .extracting(value -> value.getPropertyPath().toString())
                    .contains("schoolId", "decision", "expectedVersion", "reason");
            assertThat(factory.getValidator().validate(posting))
                    .extracting(value -> value.getPropertyPath().toString())
                    .contains("schoolId", "financialYearId", "businessDate",
                            "eventType", "maxEntries", "reason");
        }
    }
}
