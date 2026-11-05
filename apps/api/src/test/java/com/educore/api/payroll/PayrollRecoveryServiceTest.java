package com.educore.api.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PayrollRecoveryServiceTest {
    private static final LocalDate MONTH = LocalDate.of(2025, 10, 1);
    private static final UUID STAFF = UUID.randomUUID(), ISSUE = UUID.randomUUID(), RUN = UUID.randomUUID();

    @Test void includesOnlyPostedCurrentMonthAndPreservesExactCents() {
        var current = candidate(MONTH, "33.35", "INR", null);
        var plan = plan(List.of(current), "POSTED", null);
        assertThat(plan.blockers()).isEmpty();
        assertThat(plan.byStaff().get(STAFF)).singleElement().satisfies(line -> {
            assertThat(line.amount()).isEqualByComparingTo("33.35");
            assertThat(line.item().installmentId()).isEqualTo(current.installmentId());
            assertThat(line.item().reservationStatus()).isEqualTo("ACTIVE");
        });
        assertThatThrownBy(() -> plan.byStaff().get(STAFF).clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> plan.byStaff().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void doesNotCatchUpPastInstallmentsOrCollectFutureInstallments() {
        var plan = plan(List.of(candidate(MONTH.minusMonths(1), "33.33", "INR", null),
                candidate(MONTH.plusMonths(1), "33.35", "INR", null)), "POSTED", null);
        assertThat(plan.byStaff()).isEmpty();
        assertThat(plan.warnings().get(STAFF)).containsExactly("ADVANCE_MISSED_INSTALLMENT_REVIEW");
    }

    @Test void pendingRejectedAndReversedDisbursementsNeverReduceSalary() {
        for (String status : List.of("SUBMITTED", "REJECTED", "REVERSED")) {
            var plan = plan(List.of(candidate(MONTH, "33.33", "INR", null)), status, null);
            assertThat(plan.byStaff()).isEmpty();
            assertThat(plan.warnings().get(STAFF)).containsExactly("ADVANCE_NOT_DISBURSED");
        }
    }

    @Test void unverifiedDisbursementFailsClosed() {
        var plan = PayrollRecoveryService.calculate(MONTH, "INR", null,
                List.of(candidate(MONTH, "33.33", "INR", null)), Map.of());
        assertThat(plan.byStaff()).isEmpty();
        assertThat(plan.blockers().get(STAFF)).containsExactly("ADVANCE_DISBURSEMENT_UNVERIFIED");
    }

    @Test void ownRunCanRevalidateItsReservationButAnotherRunCannotReuseIt() {
        var source = candidate(MONTH, "33.33", "INR", RUN);
        assertThat(plan(List.of(source), "POSTED", RUN).byStaff().get(STAFF)).hasSize(1);
        var other = plan(List.of(source), "POSTED", UUID.randomUUID());
        assertThat(other.byStaff()).isEmpty();
        assertThat(other.blockers().get(STAFF)).containsExactly("ADVANCE_RECOVERY_ALREADY_RESERVED");
    }

    @Test void duplicateSourceBlocksInsteadOfDoubleDeducting() {
        var source = candidate(MONTH, "33.33", "INR", null);
        var plan = plan(List.of(source, source), "POSTED", null);
        assertThat(plan.byStaff().get(STAFF)).hasSize(1);
        assertThat(plan.blockers().get(STAFF)).containsExactly("ADVANCE_RECOVERY_SOURCE_DUPLICATED");
    }

    @Test void keepsMultipleApprovedAdvancesAsDistinctFrozenSources() {
        var first = candidate(MONTH, "20.01", "INR", null);
        var second = new PayrollRecoveryService.Candidate(STAFF,UUID.randomUUID(),"SYN-ADVANCE-2",UUID.randomUUID(),
                MONTH,new BigDecimal("12.34"),"INR",UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),null);
        var plan = PayrollRecoveryService.calculate(MONTH,"INR",null,List.of(first,second),
                Map.of(ISSUE,"POSTED",second.journalId(),"POSTED"));
        assertThat(plan.blockers()).isEmpty();
        assertThat(plan.byStaff().get(STAFF)).extracting(PayrollRecoveryService.Line::amount)
                .containsExactly(new BigDecimal("20.01"),new BigDecimal("12.34"));
    }

    @Test void invalidCurrencyNonpositiveSubcentAndExcessiveAmountsBlock() {
        for (String amount : List.of("0.00", "-1.00", "0.001", "1000000000000.01")) {
            var plan = plan(List.of(candidate(MONTH, amount, "INR", null)), "POSTED", null);
            assertThat(plan.byStaff()).isEmpty();
            assertThat(plan.blockers().get(STAFF)).containsExactly("ADVANCE_RECOVERY_EVIDENCE_INVALID");
        }
        assertThat(plan(List.of(candidate(MONTH,"33.33","USD",null)),"POSTED",null).blockers().get(STAFF))
                .containsExactly("ADVANCE_RECOVERY_EVIDENCE_INVALID");
    }

    private static PayrollRecoveryService.Plan plan(List<PayrollRecoveryService.Candidate> sources, String status, UUID run) {
        return PayrollRecoveryService.calculate(MONTH,"INR",run,sources,Map.of(ISSUE,status));
    }
    private static PayrollRecoveryService.Candidate candidate(LocalDate month, String amount, String currency, UUID run) {
        return new PayrollRecoveryService.Candidate(STAFF,UUID.randomUUID(),"SYN-ADVANCE",UUID.randomUUID(),month,
                new BigDecimal(amount),currency,UUID.randomUUID(),UUID.randomUUID(),ISSUE,run);
    }
}
