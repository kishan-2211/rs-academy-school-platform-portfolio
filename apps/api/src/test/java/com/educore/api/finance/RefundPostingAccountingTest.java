package com.educore.api.finance;

import static com.educore.api.accounting.AccountingModuleBridge.*;
import static com.educore.api.finance.FeeAccountingService.*;
import static org.assertj.core.api.Assertions.*;

import com.educore.api.common.api.ApiException;
import com.educore.api.identity.AuthenticatedUser;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Exact partial-allocation and common journal rule units, not PostgreSQL or provider acceptance. */
class RefundPostingAccountingTest {
    private final UUID payment = UUID.randomUUID(), completion = UUID.randomUUID(), account = UUID.randomUUID();
    private final UUID clearing = UUID.randomUUID(), receivable = UUID.randomUUID(), income = UUID.randomUUID(), otherIncome = UUID.randomUUID();
    private final UUID charge = UUID.randomUUID(), otherCharge = UUID.randomUUID(), head = UUID.randomUUID(), otherHead = UUID.randomUUID();
    private final UUID originalAllocation = UUID.randomUUID(), otherOriginalAllocation = UUID.randomUUID();

    @Test void actualFinancialPreparationAndExplicitRepairRequireTheCallersTransaction() throws Exception {
        for (String method : List.of("prepareRefundPosting", "reprepareRefundPosting")) {
            Class<?>[] parameters = method.equals("prepareRefundPosting")
                    ? new Class<?>[]{AuthenticatedUser.class, UUID.class, UUID.class, UUID.class, LocalDate.class, String.class}
                    : new Class<?>[]{AuthenticatedUser.class, UUID.class, UUID.class, UUID.class, LocalDate.class, UUID.class, UUID.class, UUID.class, String.class};
            var annotation = FeeAccountingService.class.getDeclaredMethod(method, parameters).getAnnotation(Transactional.class);
            assertThat(annotation.propagation()).isEqualTo(Propagation.MANDATORY);
            assertThat(annotation.readOnly()).isFalse();
        }
    }

    @Test void exactFrozenPartialAllocationsAreReturnedWithoutChangingOriginals() {
        var proof = proof("CASH"); var rows = allocations();
        assertThat(validateRefundAllocations(money("30.25"), rows, proof)).containsExactly(rows.get(0).value(), rows.get(1).value());
        assertThat(proof.allocations().getFirst().amount()).isEqualByComparingTo("100.00");
        assertThatThrownBy(() -> proof.allocations().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void emptyDuplicateChangedOrOverOriginalAmountsCannotBecomeRefundJournalEvidence() {
        var valid = allocations();
        var invalids = List.of(List.<RefundAllocation>of(), List.of(valid.getFirst(), valid.getFirst()),
                List.of(new RefundAllocation(UUID.randomUUID(), valid.getFirst().value())),
                List.of(new RefundAllocation(originalAllocation, allocation(completion, otherCharge, head, "10.25"))),
                List.of(new RefundAllocation(originalAllocation, allocation(completion, charge, otherHead, "10.25"))),
                List.of(new RefundAllocation(originalAllocation, allocation(completion, charge, head, "100.01"))));
        for (var rows : invalids) {
            assertThatThrownBy(() -> validateRefundAllocations(money("30.25"), rows, proof("CASH"))).isInstanceOf(ApiException.class);
        }
    }

    @Test void subCentZeroAndWrongTotalsNeverGetRoundedIntoFinancialProof() {
        for (String amount : List.of("0.00", "-0.01", "10.251")) {
            var rows = List.of(new RefundAllocation(originalAllocation, allocation(completion, charge, head, amount)));
            assertThatThrownBy(() -> validateRefundAllocations(new BigDecimal(amount), rows, proof("CASH"))).isInstanceOf(ApiException.class);
        }
        assertThatThrownBy(() -> validateRefundAllocations(money("30.24"), allocations(), proof("CASH"))).isInstanceOf(ApiException.class);
    }

    @Test void cashCounterJournalDebitsOnlyOriginalAllocatedIncomeAndCreditsClearing() {
        var values = allocations().stream().map(RefundAllocation::value).toList();
        var lines = journalLines(configuration("CASH"), "REFUND", List.of(source()), values);
        assertThat(lines).containsExactly(
                line(income, null, "10.25", "0.00"), line(otherIncome, null, "20.00", "0.00"),
                line(clearing, null, "0.00", "30.25"));
        assertBalanced(lines, "30.25");
    }

    @Test void accrualCounterJournalUsesOriginalLearnerReceivableAndNotIncome() {
        var lines = journalLines(configuration("ACCRUAL"), "REFUND", List.of(source()),
                allocations().stream().map(RefundAllocation::value).toList());
        assertThat(lines).containsExactly(line(receivable, account, "30.25", "0.00"), line(clearing, null, "0.00", "30.25"));
        assertBalanced(lines, "30.25");
    }

    @Test void commonRuleMergesSharedIncomeWithoutLosingExactPartialAmounts() {
        var configuration = new EffectiveConfiguration(UUID.randomUUID(), UUID.randomUUID(), LocalDate.of(2025,4,1),
                "CASH", "INR", null, null, Map.of(head, income, otherHead, income), Map.of("ONLINE_PROVIDER", clearing));
        var lines = journalLines(configuration, "REFUND", List.of(source()), allocations().stream().map(RefundAllocation::value).toList());
        assertThat(lines).containsExactly(line(income, null, "30.25", "0.00"), line(clearing, null, "0.00", "30.25"));
    }

    @Test void currentReadinessUsesOriginalClearingAndOnlyTheRelevantEconomicAccounts() {
        var values = allocations().stream().map(RefundAllocation::value).toList();
        assertThat(refundAccountUses(proof("CASH"), values)).containsExactly(new FeeAccountUse(clearing, FeeAccountUseType.ONLINE_CLEARING),
                new FeeAccountUse(income, FeeAccountUseType.INCOME), new FeeAccountUse(otherIncome, FeeAccountUseType.INCOME));
        assertThat(refundAccountUses(proof("ACCRUAL"), values)).containsExactly(new FeeAccountUse(clearing, FeeAccountUseType.ONLINE_CLEARING),
                new FeeAccountUse(receivable, FeeAccountUseType.RECEIVABLE));
    }

    @Test void oldFullReceiptReversalRulesRemainDistinctFromPartialRefundSource() {
        var originalSource = new SourceEntry(UUID.randomUUID(), account, null, "PAYMENT_REVERSAL", "DEBIT", money("200.00"),
                LocalDate.of(2026,10,20), "Synthetic full reversal", "FEE_PAYMENT_REVERSAL", payment, UUID.randomUUID(),
                Instant.parse("2026-10-20T06:00:00Z"), "INR", null, null, null, "ONLINE_PROVIDER", "SYN/000001");
        var originalValues = List.of(allocation(payment, charge, head, "100.00"), allocation(payment, otherCharge, otherHead, "100.00"));
        assertBalanced(journalLines(configuration("CASH"), "PAYMENT_REVERSAL", List.of(originalSource), originalValues), "200.00");
        assertBalanced(journalLines(configuration("ACCRUAL"), "PAYMENT_REVERSAL", List.of(originalSource), originalValues), "200.00");
    }

    private OriginalPaymentPostingProof proof(String basis) {
        return new OriginalPaymentPostingProof(payment, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), LocalDate.of(2025,10,1), basis, "INR", clearing,
                basis.equals("ACCRUAL") ? receivable : null, Map.of(head, income, otherHead, otherIncome),
                List.of(new OriginalPaymentAllocation(originalAllocation, charge, head, money("100.00")),
                        new OriginalPaymentAllocation(otherOriginalAllocation, otherCharge, otherHead, money("100.00"))), "a".repeat(64), "b".repeat(64));
    }
    private EffectiveConfiguration configuration(String basis) {
        var proof = proof(basis);
        return new EffectiveConfiguration(proof.configurationId(), proof.originalFinancialYearId(), proof.originalPostingDate(),
                basis, "INR", proof.receivableAccountId(), null, proof.incomeAccounts(), Map.of("ONLINE_PROVIDER", clearing));
    }
    private SourceEntry source() {
        return new SourceEntry(UUID.randomUUID(), account, null, "REFUND", "DEBIT", money("30.25"), LocalDate.of(2026,10,20),
                "Synthetic verified refund", "GATEWAY_REFUND_POSTING", completion, null, Instant.parse("2026-10-20T06:00:00Z"),
                "INR", null, null, null, "ONLINE_PROVIDER", "SYN/000001");
    }
    private List<RefundAllocation> allocations() {
        return List.of(new RefundAllocation(originalAllocation, allocation(completion, charge, head, "10.25")),
                new RefundAllocation(otherOriginalAllocation, allocation(completion, otherCharge, otherHead, "20.00")));
    }
    private AllocationEvidence allocation(UUID sourceId, UUID chargeId, UUID headId, String amount) {
        return new AllocationEvidence(sourceId, chargeId, headId, "SYNTHETIC_HEAD", "Synthetic fee", new BigDecimal(amount));
    }
    private static BigDecimal money(String amount) { return new BigDecimal(amount); }
    private static FeeJournalLine line(UUID id, UUID accountId, String debit, String credit) {
        return new FeeJournalLine(id, accountId, money(debit), money(credit), "Fee refund");
    }
    private static void assertBalanced(List<FeeJournalLine> lines, String amount) {
        assertThat(lines.stream().map(FeeJournalLine::debitAmount).reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo(amount);
        assertThat(lines.stream().map(FeeJournalLine::creditAmount).reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo(amount);
    }
}
