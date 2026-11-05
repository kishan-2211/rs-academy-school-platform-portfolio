package com.educore.api.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.educore.api.common.api.ApiException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class PayrollAdvanceScheduleTest {
    @Test
    void preservesEveryCentAndPlacesRoundingRemainderInTheFinalInstallment() {
        assertThat(PayrollAdvanceService.schedule(new BigDecimal("100.00"), 3))
                .containsExactly(new BigDecimal("33.33"), new BigDecimal("33.33"), new BigDecimal("33.34"));
        assertThat(PayrollAdvanceService.schedule(new BigDecimal("1.00"), 6))
                .containsExactly(new BigDecimal("0.16"), new BigDecimal("0.16"), new BigDecimal("0.16"),
                        new BigDecimal("0.16"), new BigDecimal("0.16"), new BigDecimal("0.20"));
        assertThat(PayrollAdvanceService.schedule(new BigDecimal("10.0000"), 2))
                .containsExactly(new BigDecimal("5.00"), new BigDecimal("5.00"));
        assertThat(PayrollAdvanceService.schedule(new BigDecimal("0.01"), 1))
                .containsExactly(new BigDecimal("0.01"));
    }

    @Test
    void conservesPrincipalAtMinimumAndMaximumAmountsForEveryAllowedInstallmentCount() {
        for (int count = 1; count <= 120; count++) {
            assertSchedule(BigDecimal.valueOf(count, 2), count);
            assertSchedule(new BigDecimal("1000000000000.00"), count);
        }
    }

    @Test
    void conservesPrincipalForDeterministicVariedAmountsAndSchedules() {
        Random random = new Random(38_2026L);
        for (int sample = 0; sample < 2_000; sample++) {
            int count = random.nextInt(120) + 1;
            long cents = count + random.nextLong(100_000_000_000_000L - count + 1);
            assertSchedule(BigDecimal.valueOf(cents, 2), count);
        }
    }

    @Test
    void rejectsMissingNonpositiveOverLimitAndSubcentAmounts() {
        assertInvalid(null, 1);
        for (BigDecimal principal : List.of(BigDecimal.ZERO, new BigDecimal("-0.01"),
                new BigDecimal("0.001"), new BigDecimal("100.001"),
                new BigDecimal("1000000000000.01"))) {
            assertInvalid(principal, 1);
        }
    }

    @Test
    void rejectsInvalidCountsAndSchedulesThatWouldContainZeroValueInstallments() {
        for (int count : List.of(Integer.MIN_VALUE, -1, 0, 121, Integer.MAX_VALUE)) {
            assertInvalid(new BigDecimal("100.00"), count);
        }
        assertInvalid(new BigDecimal("0.01"), 2);
        assertInvalid(new BigDecimal("1.19"), 120);
    }

    private static void assertSchedule(BigDecimal principal, int count) {
        List<BigDecimal> amounts = PayrollAdvanceService.schedule(principal, count);
        assertThat(amounts).hasSize(count).allSatisfy(amount -> {
            assertThat(amount).isPositive();
            assertThat(amount.scale()).isEqualTo(2);
        });
        assertThat(amounts.stream().reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo(principal);
        BigDecimal regular = amounts.getFirst();
        assertThat(amounts.subList(0, count - 1)).allSatisfy(amount -> assertThat(amount).isEqualTo(regular));
        assertThat(amounts.getLast()).isGreaterThanOrEqualTo(regular);
        assertThat(amounts.getLast().subtract(regular))
                .isLessThanOrEqualTo(BigDecimal.valueOf(count - 1L, 2));
        assertThat(PayrollAdvanceService.schedule(principal, count)).isEqualTo(amounts);
    }

    private static void assertInvalid(BigDecimal principal, int count) {
        assertThatThrownBy(() -> PayrollAdvanceService.schedule(principal, count))
                .isInstanceOfSatisfying(ApiException.class,
                        failure -> assertThat(failure.status()).isEqualTo(HttpStatus.BAD_REQUEST));
    }
}
