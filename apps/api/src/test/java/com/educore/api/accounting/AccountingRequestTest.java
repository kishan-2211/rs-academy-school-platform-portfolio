package com.educore.api.accounting;

import static com.educore.api.accounting.AccountingController.*;
import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AccountingRequestTest {
    @Test
    void acceptsConfigurableBooksBalancedVouchersAndControlledReversals() {
        UUID school = UUID.randomUUID();
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var year = new FinancialYearRequest(school, "FY_2026_27", "Financial year 2026–27",
                    LocalDate.of(2026, 4, 1), LocalDate.of(2027, 3, 31), "ACCRUAL", "INR",
                    "Synthetic financial year configuration");
            var account = new AccountRequest(school, null, "A1000.CASH", "Cash in hand", null,
                    "ASSET", "CASH", true, "ACTIVE", null, "Synthetic chart configuration");
            var vendor = new VendorRequest(school, "SYNTHETIC_VENDOR", "Synthetic vendor",
                    "Synthetic contact", "0000000000", "synthetic@example.invalid",
                    "Synthetic address", "ACTIVE", null, "Synthetic vendor configuration");
            var journal = new JournalRequest(school, UUID.randomUUID(), UUID.randomUUID(), "RECEIPT",
                    LocalDate.of(2026, 8, 28), "SYNTHETIC-1", "Synthetic balanced receipt",
                    List.of(
                            new JournalLineRequest(UUID.randomUUID(), null, null,
                                    new BigDecimal("500.00"), BigDecimal.ZERO, "Synthetic debit"),
                            new JournalLineRequest(UUID.randomUUID(), null, null,
                                    BigDecimal.ZERO, new BigDecimal("500.00"), "Synthetic credit")));
            var reversal = new ReversalRequest(school, UUID.randomUUID(),
                    LocalDate.of(2026, 8, 29), "Synthetic correction by counter-voucher");

            assertThat(factory.getValidator().validate(year)).isEmpty();
            assertThat(factory.getValidator().validate(account)).isEmpty();
            assertThat(factory.getValidator().validate(vendor)).isEmpty();
            assertThat(factory.getValidator().validate(journal)).isEmpty();
            assertThat(factory.getValidator().validate(reversal)).isEmpty();
        }
    }

    @Test
    void rejectsMissingScopeUnsafeMoneyAndMissingMakerCheckerEvidence() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var year = new FinancialYearRequest(null, "!", "", null, null, "", "RUPEES", "");
            var account = new AccountRequest(null, null, "!", "", null, "", "", false,
                    "", -1L, null);
            var vendor = new VendorRequest(null, "!", "", null, null, "invalid", null,
                    "", -1L, null);
            var journal = new JournalRequest(null, null, null, "", null, null, "", List.of(
                    new JournalLineRequest(null, null, null, new BigDecimal("-1"),
                            new BigDecimal("1000000000001"), null)));
            var decision = new DecisionRequest(null, "", "", -1);
            var reversal = new ReversalRequest(null, null, null, "");

            assertThat(factory.getValidator().validate(year))
                    .extracting(value -> value.getPropertyPath().toString())
                    .contains("schoolId", "code", "name", "startsOn", "endsOn",
                            "accountingBasis", "currencyCode", "reason");
            assertThat(factory.getValidator().validate(account))
                    .extracting(value -> value.getPropertyPath().toString())
                    .contains("schoolId", "code", "name", "accountType", "category",
                            "status", "expectedVersion");
            assertThat(factory.getValidator().validate(vendor))
                    .extracting(value -> value.getPropertyPath().toString())
                    .contains("schoolId", "code", "name", "email", "status", "expectedVersion");
            assertThat(factory.getValidator().validate(journal))
                    .extracting(value -> value.getPropertyPath().toString())
                    .contains("schoolId", "financialYearId", "accountingPeriodId", "voucherType",
                            "postingDate", "narration", "lines", "lines[0].accountId",
                            "lines[0].debitAmount", "lines[0].creditAmount");
            assertThat(factory.getValidator().validate(decision))
                    .extracting(value -> value.getPropertyPath().toString())
                    .contains("schoolId", "decision", "reason", "expectedVersion");
            assertThat(factory.getValidator().validate(reversal))
                    .extracting(value -> value.getPropertyPath().toString())
                    .contains("schoolId", "requestedAccountingPeriodId", "requestedPostingDate", "reason");
        }
    }
}
