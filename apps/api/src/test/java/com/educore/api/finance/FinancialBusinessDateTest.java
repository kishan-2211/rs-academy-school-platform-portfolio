package com.educore.api.finance;

import static org.assertj.core.api.Assertions.assertThat;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class FinancialBusinessDateTest {
    @Test void indianFinancialDayDoesNotStayOnYesterdaysUtcDateAfterLocalMidnight() {
        Clock utc = Clock.fixed(Instant.parse("2026-10-09T20:00:00Z"), ZoneOffset.UTC);
        assertThat(FinanceService.businessDate(utc, "Asia/Kolkata")).isEqualTo(LocalDate.of(2026,10,10));
        assertThat(FinanceService.businessDate(utc, "UTC")).isEqualTo(LocalDate.of(2026,10,9));
    }
    @Test void configuredSchoolDateIsIndependentOfTheHostClockZone() {
        Clock india = Clock.fixed(Instant.parse("2026-10-10T03:00:00Z"), java.time.ZoneId.of("Asia/Kolkata"));
        assertThat(FinanceService.businessDate(india, "America/New_York")).isEqualTo(LocalDate.of(2026,10,9));
        assertThat(FinanceService.businessDate(india, "Asia/Kolkata")).isEqualTo(LocalDate.of(2026,10,10));
    }
}
