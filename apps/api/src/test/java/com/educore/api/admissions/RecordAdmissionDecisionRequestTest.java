package com.educore.api.admissions;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class RecordAdmissionDecisionRequestTest {

    @Test
    void validatesReasonExpiryAndOptimisticVersion() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var valid = new RecordAdmissionDecisionRequest(
                    AdmissionDecision.OFFER,
                    "READY_FOR_OFFER",
                    "Synthetic test summary.",
                    LocalDate.now().plusDays(7),
                    2L);
            var invalid = new RecordAdmissionDecisionRequest(
                    null,
                    "",
                    null,
                    LocalDate.now().minusDays(1),
                    -1L);

            assertThat(factory.getValidator().validate(valid)).isEmpty();
            assertThat(factory.getValidator().validate(invalid))
                    .extracting(violation -> violation.getPropertyPath().toString())
                    .contains("decision", "reasonCode", "offerExpiresOn", "expectedVersion");
        }
    }
}
