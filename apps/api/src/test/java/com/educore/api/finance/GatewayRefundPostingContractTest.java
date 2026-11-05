package com.educore.api.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.educore.api.accounting.AccountingModuleBridge;
import com.educore.api.common.api.ApiException;
import com.educore.api.identity.SecurityAuditService;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Validation;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

class GatewayRefundPostingContractTest {
    private static final UUID SCHOOL = UUID.fromString("7c000000-0000-4000-8000-000000000001");
    private static final UUID REFUND = UUID.fromString("7c000000-0000-4000-8000-000000000002");
    private static final UUID YEAR = UUID.fromString("7c000000-0000-4000-8000-000000000003");
    private static final LocalDate DAY = LocalDate.of(2026, 10, 20);
    private static final JsonMapper JSON = JsonMapper.builder().changeDefaultPropertyInclusion(
            value -> value.withValueInclusion(JsonInclude.Include.NON_NULL)).build();

    @Test void noVerifiedSuccessHasExplicitNullsAndDoesNotInventMoneyOrProviderFacts() {
        var context = new GatewayRefundPostingModels.Context(SCHOOL, REFUND, UUID.randomUUID(), "SYN-000001", UUID.randomUUID(),
                "Synthetic learner", "Synthetic session", "ACTIVE", "INR", new BigDecimal("800.00"), null,
                "NO_VERIFIED_SUCCESS", null, null, null, false, false, null);
        var value = JSON.valueToTree(context);
        for (String key : new String[]{"verifiedRefundAmount", "processedAtExact", "processedOn", "schoolTimeZone", "posting"}) assertThat(value.get(key).isNull()).isTrue();
        assertThat(value.get("canPost").booleanValue()).isFalse();
        assertThat(value.toString()).doesNotContain("captureId", "provider", "credentials", "evidence_sha256");
    }

    @Test void retainedFinancialPostingAndPendingAccountingRemainDistinctFromOriginalGrossReceipt() {
        var posting = new GatewayRefundPostingModels.Posting(UUID.randomUUID(), REFUND, UUID.randomUUID(), UUID.randomUUID(),
                new BigDecimal("50.25"), "INR", DAY, Instant.parse("2026-10-20T01:00:00Z"), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "SUBMITTED");
        var value = JSON.valueToTree(posting);
        assertThat(value.get("amount").decimalValue()).isEqualByComparingTo("50.25");
        assertThat(value.get("journalStatus").stringValue()).isEqualTo("SUBMITTED");
        assertThat(value.has("receiptNumber")).isFalse();
        assertThat(posting.toString()).doesNotContain("50.25", posting.accountId().toString());
    }

    @Test void commandRequiresOnlyReviewedScopeIdentityOpenBookDateAndReasonNotClientMoney() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertThat(validator.validate(new GatewayRefundPostingModels.Command(SCHOOL, UUID.randomUUID(), YEAR, DAY, "Synthetic reviewed posting"))).isEmpty();
            var invalid = new GatewayRefundPostingModels.Command(null, null, null, null, " ");
            assertThat(validator.validate(invalid).stream().map(violation -> violation.getPropertyPath().toString()).collect(java.util.stream.Collectors.toSet()))
                    .containsExactlyInAnyOrder("schoolId", "clientRequestId", "financialYearId", "postingOn", "reason");
        }
        var names = java.util.Arrays.stream(GatewayRefundPostingModels.Command.class.getRecordComponents()).map(java.lang.reflect.RecordComponent::getName).toList();
        assertThat(names).containsExactly("schoolId", "clientRequestId", "financialYearId", "postingOn", "reason");
    }

    @Test void exactRetryFingerprintFreezesEveryEconomicPostingTermAndReasonLength() {
        String original = GatewayRefundPostingService.fingerprint(REFUND, YEAR, DAY, "Synthetic reason|with separator");
        assertThat(original).matches("[a-f0-9]{64}");
        assertThat(GatewayRefundPostingService.fingerprint(REFUND, YEAR, DAY, "Synthetic reason|with separator")).isEqualTo(original);
        assertThat(GatewayRefundPostingService.fingerprint(UUID.randomUUID(), YEAR, DAY, "Synthetic reason|with separator")).isNotEqualTo(original);
        assertThat(GatewayRefundPostingService.fingerprint(REFUND, UUID.randomUUID(), DAY, "Synthetic reason|with separator")).isNotEqualTo(original);
        assertThat(GatewayRefundPostingService.fingerprint(REFUND, YEAR, DAY.plusDays(1), "Synthetic reason|with separator")).isNotEqualTo(original);
        assertThat(GatewayRefundPostingService.fingerprint(REFUND, YEAR, DAY, "Synthetic reason|with separator!")).isNotEqualTo(original);
    }

    @Test void missingActualHumanTransactionCannotReadOrCreateFinancialPosting() {
        var service = new GatewayRefundPostingService(mock(NamedParameterJdbcTemplate.class), mock(FinanceService.class), mock(FeeAccountingService.class),
                mock(AccountingModuleBridge.class), mock(SecurityAuditService.class), Clock.systemUTC());
        assertThatThrownBy(() -> service.inspect(null, SCHOOL, REFUND)).isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.code()).isEqualTo("REFUND_POSTING_DENIED"));
        assertThatThrownBy(() -> service.post(null, REFUND, new GatewayRefundPostingModels.Command(SCHOOL, UUID.randomUUID(), YEAR, DAY, "Synthetic reviewed posting")))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.code()).isEqualTo("REFUND_POSTING_DENIED"));
    }

    @Test void repairCommandFreezesPredecessorOpenBookDateAndReasonWithoutAcceptingClientMoney() {
        try(var factory=Validation.buildDefaultValidatorFactory()) {
            var valid=new GatewayRefundPostingModels.JournalRepairCommand(SCHOOL,UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),YEAR,DAY,"Synthetic accounting-only correction");
            assertThat(factory.getValidator().validate(valid)).isEmpty();
            assertThat(factory.getValidator().validate(new GatewayRefundPostingModels.JournalRepairCommand(null,null,null,null,null,null," "))
                    .stream().map(v->v.getPropertyPath().toString()).collect(java.util.stream.Collectors.toSet()))
                    .containsExactlyInAnyOrder("schoolId","clientRequestId","expectedBatchId","expectedJournalId","financialYearId","postingOn","reason");
            assertThat(valid.toString()).doesNotContain(SCHOOL.toString(),valid.expectedJournalId().toString(),"Synthetic accounting-only");
        }
        assertThat(java.util.Arrays.stream(GatewayRefundPostingModels.JournalRepairCommand.class.getRecordComponents()).map(java.lang.reflect.RecordComponent::getName).toList())
                .containsExactly("schoolId","clientRequestId","expectedBatchId","expectedJournalId","financialYearId","postingOn","reason");
    }

    @Test void retainedEconomicDateAndAccountingCorrectionDateAreSerializedSeparately() {
        var posting=new GatewayRefundPostingModels.Posting(UUID.randomUUID(),REFUND,UUID.randomUUID(),UUID.randomUUID(),new BigDecimal("50.25"),"INR",DAY,
                Instant.parse("2026-10-20T01:00:00Z"),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"SUBMITTED",YEAR,DAY.plusDays(1));
        var json=JSON.valueToTree(posting);
        assertThat(json.get("postingOn").stringValue()).isEqualTo("2026-10-20");
        assertThat(json.get("journalPostingOn").stringValue()).isEqualTo("2026-10-21");
        assertThat(json.get("journalFinancialYearId").stringValue()).isEqualTo(YEAR.toString());
    }

    @Test void missingActualHumanCannotRepairEvenWithWellFormedAccountingTerms() {
        var service=new GatewayRefundPostingService(mock(NamedParameterJdbcTemplate.class),mock(FinanceService.class),mock(FeeAccountingService.class),
                mock(AccountingModuleBridge.class),mock(SecurityAuditService.class),Clock.systemUTC());
        assertThatThrownBy(()->service.repairJournal(null,REFUND,new GatewayRefundPostingModels.JournalRepairCommand(SCHOOL,UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),YEAR,DAY,"Synthetic correction")))
                .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("REFUND_POSTING_DENIED"));
    }
}
