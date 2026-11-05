package com.educore.api.communication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.time.Instant;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;

class PushDeliveryPreparationTest {
    @Test
    void overnightQuietHoursUseTheRecipientsZoneAndExcludeTheEndBoundary() {
        assertThat(delay("2026-09-08T17:30:00Z","Asia/Kolkata",22,7)).isEqualTo(28800);
        assertThat(delay("2026-09-08T16:30:00Z","Asia/Kolkata",22,7)).isEqualTo(32400);
        assertThat(delay("2026-09-09T01:30:00Z","Asia/Kolkata",22,7)).isZero();
        assertThat(delay("2026-09-09T04:30:00Z","Asia/Kolkata",9,11)).isEqualTo(3600);
        assertThat(delay("2026-09-09T06:30:00Z","Asia/Kolkata",9,11)).isZero();
    }

    @Test
    void daylightSavingOverlapWaitsUntilTheLaterEndAndGapsResolveForward() {
        assertThat(PushDeliveryPreparation.quietDelay(Instant.parse("2026-11-01T05:30:00Z"),"America/New_York",
                LocalTime.MIDNIGHT,LocalTime.of(1,45),false)).isEqualTo(4500);
        assertThat(PushDeliveryPreparation.quietDelay(Instant.parse("2026-03-08T06:30:00Z"),"America/New_York",
                LocalTime.of(22,0),LocalTime.of(2,30),false)).isEqualTo(3600);
    }

    @Test
    void OnlyExplicitlyApprovedEmergencyBypassesQuietHoursAndInvalidWindowsFailClosed() {
        Instant now=Instant.parse("2026-09-08T17:30:00Z");
        assertThat(PushDeliveryPreparation.quietDelay(now,"Asia/Kolkata",LocalTime.of(22,0),LocalTime.of(7,0),true)).isZero();
        assertThat(PushDeliveryPreparation.quietDelay(now,"Asia/Kolkata",null,null,false)).isZero();
        assertThatThrownBy(() -> PushDeliveryPreparation.quietDelay(now,"Asia/Kolkata",LocalTime.NOON,null,false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PushDeliveryPreparation.quietDelay(now,"invalid/timezone",LocalTime.NOON,LocalTime.MIDNIGHT,false))
                .isInstanceOf(java.time.DateTimeException.class);
    }

    @Test
    void externalContactPreservesTransactionalRestrictionsAndRejectsUnknownStates() {
        assertThat(CommunicationService.allowsExternalContact("ALLOWED","GENERAL")).isTrue();
        assertThat(CommunicationService.allowsExternalContact("TRANSACTIONAL_ONLY","FEES")).isTrue();
        assertThat(CommunicationService.allowsExternalContact("TRANSACTIONAL_ONLY","GENERAL")).isFalse();
        assertThat(CommunicationService.allowsExternalContact("TRANSACTIONAL_ONLY",null)).isFalse();
        assertThat(CommunicationService.allowsExternalContact("BLOCKED","EMERGENCY")).isFalse();
        assertThat(CommunicationService.allowsExternalContact(null,"EMERGENCY")).isFalse();
        assertThat(CommunicationService.allowsExternalContact("UNKNOWN","EMERGENCY")).isFalse();
    }

    private static long delay(String now,String zone,int start,int end) {
        return PushDeliveryPreparation.quietDelay(Instant.parse(now),zone,LocalTime.of(start,0),LocalTime.of(end,0),false);
    }
}
