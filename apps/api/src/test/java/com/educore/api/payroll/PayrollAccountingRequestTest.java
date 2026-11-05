package com.educore.api.payroll;

import static com.educore.api.payroll.PayrollAccountingController.*;
import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PayrollAccountingRequestTest {
    @Test
    void acceptsScopedMappingAndPaymentEvidence() {
        UUID school = UUID.randomUUID(), year = UUID.randomUUID();
        var configuration = new ConfigurationRequest(school, year, null, 1, LocalDate.of(2025, 4, 1),
                UUID.randomUUID(), List.of(new ComponentMappingRequest(UUID.randomUUID(), UUID.randomUUID(), null)),
                List.of(new TenderMappingRequest("CASH", UUID.randomUUID())), "Synthetic accounting configuration");
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertThat(validator.validate(configuration)).isEmpty();
            assertThat(validator.validate(new UpdateConfigurationRequest(configuration, 0L))).isEmpty();
            assertThat(validator.validate(new AccrualRequest(school, year, LocalDate.of(2025, 8, 31),
                    "Synthetic accrual evidence"))).isEmpty();
            assertThat(validator.validate(new PaymentRequest(school, year, LocalDate.of(2025, 9, 1),
                    "BANK_TRANSFER", "SYNTHETIC-REFERENCE", List.of(UUID.randomUUID()),
                    "Synthetic salary payment evidence"))).isEmpty();
        }
    }

    @Test
    void rejectsUnscopedIncompleteOrUnversionedMutations() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertThat(validator.validate(new ConfigurationRequest(null, null, null, 0, null, null,
                    List.of(), List.of(), ""))).extracting(v -> v.getPropertyPath().toString())
                    .contains("schoolId", "financialYearId", "revisionNumber", "effectiveFrom",
                            "salaryPayableAccountId", "componentMappings", "tenderMappings", "reason");
            assertThat(validator.validate(new PaymentRequest(null, null, null, "", null, List.of(), "")))
                    .extracting(v -> v.getPropertyPath().toString()).contains("schoolId", "financialYearId",
                            "postingDate", "paymentMethod", "payrollRunStaffResultIds", "reason");
            assertThat(validator.validate(new UpdateConfigurationRequest(null, -1L)))
                    .extracting(v -> v.getPropertyPath().toString()).contains("configuration", "expectedVersion");
            assertThat(validator.validate(new ConfigurationDecisionRequest(null, "", null, "")))
                    .extracting(v -> v.getPropertyPath().toString())
                    .contains("schoolId", "decision", "expectedVersion", "reason");
        }
    }
}
