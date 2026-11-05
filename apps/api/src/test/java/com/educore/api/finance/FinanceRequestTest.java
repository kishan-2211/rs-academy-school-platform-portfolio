package com.educore.api.finance;

import static org.assertj.core.api.Assertions.assertThat;

import com.educore.api.finance.FinanceController.*;
import jakarta.validation.Validation;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FinanceRequestTest {
    @Test
    void additiveReceivedFundsRequestPreservesLegacyCashAndDoesNotEchoPrivateEvidence() {
        var school = UUID.randomUUID(); var account = UUID.randomUUID();
        var legacy = new PaymentRequest(school, account, "CASH", null, new BigDecimal("100.00"),
                LocalDate.of(2026, 10, 10), "Synthetic cash", List.of());
        assertThat(legacy.actualReceivedAt()).isNull();
        assertThat(legacy.postingOn()).isNull();
        assertThat(legacy.bankCreditVerification()).isNull();
        assertThat(legacy.reviewReason()).isNull();
        var credit = new ManualCollectionEvidence.BankCreditVerification("BANK_APP_CREDIT", true, "SYN-BANK-001");
        var verified = new PaymentRequest(school, account, "SCHOOL_UPI", "SYN-BANK-001", new BigDecimal("100.00"),
                LocalDate.of(2026, 10, 10), "Synthetic received credit", List.of(),
                java.time.Instant.parse("2026-10-10T04:31:00Z"), LocalDate.of(2026, 10, 11), credit,
                "Synthetic open-day reviewed posting");
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(verified)).isEmpty();
        }
        assertThat(verified.toString()).isEqualTo("PaymentRequest[scoped manual collection]")
                .doesNotContain("SYN-BANK-001", "Synthetic received credit", "2026-10-10");
    }

    @Test
    void reviewedPostingReasonRemainsBoundedAndCannotBeBlank() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            for (String reason : List.of("", "x".repeat(241))) {
                var request = new PaymentRequest(UUID.randomUUID(), UUID.randomUUID(), "CASH", null,
                        new BigDecimal("100.00"), LocalDate.of(2026, 10, 10), null, List.of(), null,
                        LocalDate.of(2026, 10, 11), null, reason);
                assertThat(factory.getValidator().validate(request)).extracting(value -> value.getPropertyPath().toString())
                        .contains("reviewReason");
            }
        }
    }

    @Test
    void acceptsConfigurablePlanCollectionAndApprovalRequests() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var head = new FeeHeadRequest(UUID.randomUUID(), "TUITION", "Tuition fee", null,
                    "TUITION", false, "ACTIVE", null, "Synthetic setup");
            var plan = new FeePlanRequest(UUID.randomUUID(), UUID.randomUUID(), null,
                    "CLASS_6_CORE", 1, "Class VI core fees", "VI", "INR", "RSA/26",
                    "Synthetic plan", null, "Synthetic setup");
            var line = new PlanItemRequest(UUID.randomUUID(), UUID.randomUUID(), 1,
                    "April tuition", LocalDate.of(2026, 4, 10), new BigDecimal("825.00"),
                    true, "Synthetic schedule");
            var payment = new PaymentRequest(UUID.randomUUID(), UUID.randomUUID(), "CASH", null,
                    new BigDecimal("500.00"), LocalDate.of(2026, 4, 10), "Synthetic collection",
                    List.of(new PaymentAllocation(UUID.randomUUID(), new BigDecimal("500.00"))));
            var decision = new ConcessionDecisionRequest(UUID.randomUUID(), "APPROVED",
                    "Synthetic maker-checker decision", 0);
            var close = new DayCloseRequest(UUID.randomUUID(), LocalDate.of(2026, 8, 22),
                    List.of(
                            new TenderDeclaration("CASH", new BigDecimal("500.00")),
                            new TenderDeclaration("BANK_TRANSFER", new BigDecimal("100.00")),
                            new TenderDeclaration("CHEQUE", BigDecimal.ZERO),
                            new TenderDeclaration("SCHOOL_UPI", BigDecimal.ZERO),
                            new TenderDeclaration("ONLINE_PROVIDER", BigDecimal.ZERO)),
                    "Synthetic daily evidence", null);
            var closeDecision = new DayCloseDecisionRequest(UUID.randomUUID(), "APPROVED",
                    "Synthetic independent close approval", 0);

            assertThat(factory.getValidator().validate(head)).isEmpty();
            assertThat(factory.getValidator().validate(plan)).isEmpty();
            assertThat(factory.getValidator().validate(line)).isEmpty();
            assertThat(factory.getValidator().validate(payment)).isEmpty();
            assertThat(factory.getValidator().validate(decision)).isEmpty();
            assertThat(factory.getValidator().validate(close)).isEmpty();
            assertThat(factory.getValidator().validate(closeDecision)).isEmpty();
        }
    }

    @Test
    void rejectsUnsafeMoneyIdentifiersAndMissingEvidence() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var head = new FeeHeadRequest(null, "!", "", null, "", false, "", -1L, null);
            var plan = new FeePlanRequest(null, null, null, "!", 0, "", null, "RUPEES", "?",
                    null, -1L, null);
            var line = new PlanItemRequest(null, null, 0, "", null, new BigDecimal("0"),
                    false, null);
            var payment = new PaymentRequest(null, null, "", null, new BigDecimal("0"), null,
                    null, List.of(new PaymentAllocation(null, new BigDecimal("0"))));
            var reversal = new PaymentReversalRequest(null, -1, "");
            var close = new DayCloseRequest(null, null,
                    List.of(new TenderDeclaration("", new BigDecimal("-100000000.01"))), "", null);
            var closeDecision = new DayCloseDecisionRequest(null, "", "", -1);

            assertThat(factory.getValidator().validate(head))
                    .extracting(value -> value.getPropertyPath().toString())
                    .contains("schoolId", "code", "name", "category", "status", "expectedVersion");
            assertThat(factory.getValidator().validate(plan))
                    .extracting(value -> value.getPropertyPath().toString())
                    .contains("schoolId", "academicSessionId", "code", "revisionNumber", "name",
                            "currencyCode", "receiptPrefix", "expectedVersion");
            assertThat(factory.getValidator().validate(line))
                    .extracting(value -> value.getPropertyPath().toString())
                    .contains("schoolId", "feeHeadId", "sequenceNumber", "label", "dueOn", "amount");
            assertThat(factory.getValidator().validate(payment))
                    .extracting(value -> value.getPropertyPath().toString())
                    .contains("schoolId", "accountId", "paymentMethod", "amount", "receivedOn",
                            "allocations[0].chargeId", "allocations[0].amount");
            assertThat(factory.getValidator().validate(reversal))
                    .extracting(value -> value.getPropertyPath().toString())
                    .contains("schoolId", "expectedVersion", "reason");
            assertThat(factory.getValidator().validate(close))
                    .extracting(value -> value.getPropertyPath().toString())
                    .contains("schoolId", "businessDate", "declarations", "reason",
                            "declarations[0].paymentMethod", "declarations[0].declaredAmount");
            assertThat(factory.getValidator().validate(closeDecision))
                    .extracting(value -> value.getPropertyPath().toString())
                    .contains("schoolId", "decision", "reason", "expectedVersion");
        }
    }
}
